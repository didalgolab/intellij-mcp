package com.didalgo.intellij.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.ProjectScope;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves class, member, or resource symbols to source snippets.
 */
public final class SymbolSourceLookup {

    private static final Logger LOG = Logger.getInstance(SymbolSourceLookup.class);

    private SymbolSourceLookup() {
    }

    public enum Status {
        OK,
        NOT_FOUND,
        INDEXING,
        ERROR
    }

    public enum SymbolKind {
        CLASS,
        METHOD,
        FIELD,
        RESOURCE,
        FILE,
        UNKNOWN
    }

    public record Request(
            @NotNull String symbolName,
            @Nullable String methodName,
            @Nullable List<String> methodParamTypes,
            @Nullable String fieldName,
            @Nullable String moduleName,
            @Nullable Integer lineStart,
            @Nullable Integer lineEnd,
            boolean preferSource,
            boolean includeInherited,
            boolean forceDecompiled,
            boolean allowResourceLookup,
            @Nullable Integer responseDepth) {

        public Request {
            if (symbolName.isBlank()) {
                throw new IllegalArgumentException("`symbolName` must not be blank");
            }
            if (responseDepth != null && responseDepth < 0) {
                throw new IllegalArgumentException("`responseDepth` must be greater than or equal to 0");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            String symbolKey,
            String origin,
            String moduleName,
            String classpathEntry,
            String uri,
            SymbolKind kind) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            @NotNull Status status,
            @NotNull String humanMessage,
            @Nullable String sourceText,
            @Nullable String uri,
            @Nullable String symbolKey,
            @NotNull SymbolKind kind,
            @Nullable String moduleName,
            @Nullable String origin,
            int startLine,
            int endLine,
            int startOffset,
            int endOffset,
            @NotNull List<Candidate> alternatives,
            @Nullable String diagnostics) {

        public boolean isOk() {
            return status == Status.OK;
        }
    }

    public static @NotNull Result resolve(@NotNull Project project, @NotNull Request req) {
        if (DumbService.isDumb(project)) {
            return indexingResult("Indices are updating (dumb mode). Try again when indexing completes.", req);
        }
        try {
            return ReadAction.compute(() -> resolveUnderRead(project, req));
        } catch (IndexNotReadyException ex) {
            return indexingResult("IndexNotReadyException: indices are updating. Try again later.", req);
        } catch (Throwable t) {
            LOG.warn("Symbol resolution failed", t);
            return new Result(
                    Status.ERROR,
                    "Unexpected error: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    null,
                    null,
                    req.symbolName(),
                    SymbolKind.UNKNOWN,
                    req.moduleName(),
                    null,
                    0,
                    0,
                    -1,
                    -1,
                    List.of(),
                    topStack(t));
        }
    }

    private static @NotNull Result resolveUnderRead(@NotNull Project project, @NotNull Request req) {
        GlobalSearchScope scope = scopeFor(project, req.moduleName());
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        List<PsiClass> classCandidates = new ArrayList<>(Arrays.stream(facade.findClasses(req.symbolName(), scope))
                .filter(c -> req.symbolName().equals(c.getQualifiedName()))
                .toList());
        if (classCandidates.isEmpty()) {
            String shortName = extractShortName(req.symbolName());
            PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
            classCandidates.addAll(Arrays.asList(cache.getClassesByName(shortName, scope)));
        }
        if (!classCandidates.isEmpty()) {
            return resolveFromClassCandidates(project, req, classCandidates);
        }
        if (req.allowResourceLookup()) {
            ResourceResult resourceResult = resolveResource(project, req);
            if (resourceResult != null) {
                Candidate primary = candidateOf(project, resourceResult.primaryVf);
                List<Candidate> alternatives = new ArrayList<>();
                alternatives.add(primary);
                for (VirtualFile vf : resourceResult.alternatives) {
                    if (vf != resourceResult.primaryVf) {
                        alternatives.add(candidateOf(project, vf));
                    }
                }
                String snippetText = truncateByDepth(resourceResult.snippet.text, req.responseDepth());
                return new Result(
                        Status.OK,
                        resourceResult.message,
                        snippetText,
                        anchor(urlOf(resourceResult.primaryVf),
                                resourceResult.snippet.startLine,
                                resourceResult.snippet.endLine,
                                resourceResult.snippet.startOffset,
                                resourceResult.snippet.endOffset),
                        resourceResult.symbolKey,
                        SymbolKind.RESOURCE,
                        ownerModule(project, resourceResult.primaryVf),
                        "RESOURCE",
                        resourceResult.snippet.startLine,
                        resourceResult.snippet.endLine,
                        resourceResult.snippet.startOffset,
                        resourceResult.snippet.endOffset,
                        alternatives,
                        resourceResult.diagnostics);
            }
        }
        return new Result(
                Status.NOT_FOUND,
                "No class or resource matched symbolName: " + req.symbolName(),
                null,
                null,
                req.symbolName(),
                SymbolKind.UNKNOWN,
                req.moduleName(),
                null,
                0,
                0,
                -1,
                -1,
                List.of(),
                "Tried FQN and short-name class resolution, then resource lookup (if allowed).");
    }

    private static @NotNull Result resolveFromClassCandidates(Project project, Request req, List<PsiClass> classes) {
        List<PsiClass> ordered = orderClassesForBestPick(project, classes, req);
        PsiClass bestClass = ordered.getFirst();
        PsiElement target;
        SymbolKind kind;
        if (req.methodName() != null) {
            List<PsiMethod> methods = new ArrayList<>(Arrays.asList(bestClass.findMethodsByName(req.methodName(), req.includeInherited())));
            if (methods.isEmpty()) {
                return notFoundInside(bestClass, req, "No method named " + req.methodName() + " found.");
            }
            List<PsiMethod> filtered = filterByParamTypes(methods, req.methodParamTypes());
            List<PsiMethod> finalSet = filtered.isEmpty() ? methods : filtered;
            finalSet.sort(Comparator.comparing(SymbolSourceLookup::signatureOf));
            target = finalSet.getFirst();
            kind = SymbolKind.METHOD;
            List<Candidate> altMethods = new ArrayList<>();
            altMethods.add(candidateOf(target));
            for (PsiMethod method : finalSet) {
                if (method != target) {
                    altMethods.add(candidateOf(method));
                }
            }
            for (int i = 1; i < ordered.size(); i++) {
                PsiClass other = ordered.get(i);
                PsiMethod[] others = other.findMethodsByName(req.methodName(), req.includeInherited());
                for (PsiMethod method : others) {
                    altMethods.add(candidateOf(method));
                }
            }
            PsiElement elementForText = chooseElementForText(target, req.forceDecompiled(), req.preferSource());
            PsiFile psiFile = elementForText.getContainingFile();
            if (psiFile == null) {
                return problem("Resolved method has no containing file.", req, target, kind);
            }
            Snippet snippet = buildSnippetForPsi(project, psiFile, elementForText, req.lineStart(), req.lineEnd());
            String snippetText = truncateByDepth(snippet.text, req.responseDepth());
            Candidate primaryCandidate = candidateOf(elementForText);
            return new Result(
                    Status.OK,
                    altMethods.size() > 1
                            ? "Resolved method; multiple overloads exist. Returning best match and listing alternatives."
                            : "Resolved method successfully.",
                    snippetText,
                    anchor(urlOf(psiFile), snippet.startLine, snippet.endLine, snippet.startOffset, snippet.endOffset),
                    qualifiedNameOf(target),
                    kind,
                    primaryCandidate.moduleName(),
                    primaryCandidate.origin(),
                    snippet.startLine,
                    snippet.endLine,
                    snippet.startOffset,
                    snippet.endOffset,
                    dedupeCandidatesAltFirst(primaryCandidate, altMethods),
                    signatures(finalSet));
        }
        if (req.fieldName() != null) {
            List<PsiField> fields = Arrays.stream(bestClass.getFields())
                    .filter(f -> req.fieldName().equals(f.getName()))
                    .collect(Collectors.toList());
            if (fields.isEmpty()) {
                return notFoundInside(bestClass, req, "No field named " + req.fieldName() + " found.");
            }
            fields.sort(Comparator.comparing(f -> candidateOf(f).uri()));
            target = fields.getFirst();
            kind = SymbolKind.FIELD;
            List<Candidate> altFields = new ArrayList<>();
            altFields.add(candidateOf(target));
            for (int i = 1; i < ordered.size(); i++) {
                PsiClass other = ordered.get(i);
                for (PsiField field : other.getFields()) {
                    if (req.fieldName().equals(field.getName())) {
                        altFields.add(candidateOf(field));
                    }
                }
            }
            PsiElement elementForText = chooseElementForText(target, req.forceDecompiled(), req.preferSource());
            PsiFile psiFile = elementForText.getContainingFile();
            if (psiFile == null) {
                return problem("Resolved field has no containing file.", req, target, kind);
            }
            Snippet snippet = buildSnippetForPsi(project, psiFile, elementForText, req.lineStart(), req.lineEnd());
            String snippetText = truncateByDepth(snippet.text, req.responseDepth());
            Candidate primaryCandidate = candidateOf(elementForText);
            return new Result(
                    Status.OK,
                    altFields.size() > 1
                            ? "Resolved field; multiple classpath copies exist. Returning best match and listing alternatives."
                            : "Resolved field successfully.",
                    snippetText,
                    anchor(urlOf(psiFile), snippet.startLine, snippet.endLine, snippet.startOffset, snippet.endOffset),
                    qualifiedNameOf(target),
                    kind,
                    primaryCandidate.moduleName(),
                    primaryCandidate.origin(),
                    snippet.startLine,
                    snippet.endLine,
                    snippet.startOffset,
                    snippet.endOffset,
                    dedupeCandidatesAltFirst(primaryCandidate, altFields),
                    null);
        }
        PsiElement elementForText = chooseElementForText(bestClass, req.forceDecompiled(), req.preferSource());
        PsiFile psiFile = elementForText.getContainingFile();
        if (psiFile == null) {
            return problem("Resolved class has no containing file.", req, elementForText, SymbolKind.CLASS);
        }
        Snippet snippet = buildSnippetForPsi(project, psiFile, elementForText, req.lineStart(), req.lineEnd());
        String snippetText = truncateByDepth(snippet.text, req.responseDepth());
        Candidate primaryCandidate = candidateOf(elementForText);
        List<Candidate> alternatives = new ArrayList<>();
        alternatives.add(primaryCandidate);
        for (int i = 1; i < ordered.size(); i++) {
            alternatives.add(candidateOf(ordered.get(i)));
        }
        return new Result(
                Status.OK,
                alternatives.size() > 1
                        ? "Resolved class; multiple classpath copies exist. Returning best match and listing alternatives."
                        : "Resolved class successfully.",
                snippetText,
                anchor(urlOf(psiFile), snippet.startLine, snippet.endLine, snippet.startOffset, snippet.endOffset),
                qualifiedNameOf(elementForText),
                SymbolKind.CLASS,
                primaryCandidate.moduleName(),
                primaryCandidate.origin(),
                snippet.startLine,
                snippet.endLine,
                snippet.startOffset,
                snippet.endOffset,
                alternatives,
                null);
    }

    private static List<PsiClass> orderClassesForBestPick(Project project, List<PsiClass> classes, Request req) {
        Comparator<PsiClass> comparator = Comparator
                .comparing((PsiClass c) -> !Objects.equals(ownerModule(project, c), req.moduleName()))
                .thenComparing(c -> !(req.preferSource() && !isCompiled(c.getContainingFile())))
                .thenComparing(c -> isJarFile(c.getContainingFile()))
                .thenComparing(SymbolSourceLookup::fileUrlOf, Comparator.nullsLast(String::compareTo));
        return classes.stream().sorted(comparator).collect(Collectors.toList());
    }

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt",
            "properties",
            "xml",
            "json",
            "yml",
            "yaml",
            "csv",
            "md",
            "ini",
            "conf",
            "cfg",
            "proto",
            "sql",
            "graphql",
            "gql");

    private static @Nullable ResourceResult resolveResource(Project project, Request req) {
        String raw = req.symbolName();
        List<String> attempts = new ArrayList<>();
        String pathForm = raw.replace('\\', '/');
        if (pathForm.contains("/")) {
            attempts.add(stripLeadingSlash(pathForm));
        } else {
            if (hasExtension(raw)) {
                int lastDot = raw.lastIndexOf('.');
                String base = raw.substring(0, lastDot).replace('.', '/');
                String ext = raw.substring(lastDot + 1);
                attempts.add(base + "." + ext);
            }
            attempts.add(lastSegment(pathForm));
        }
        VirtualFile[] roots = classRoots(project, req.moduleName());
        List<VirtualFile> found = new ArrayList<>();
        for (String attempt : attempts) {
            for (VirtualFile root : roots) {
                VirtualFile candidate = root.findFileByRelativePath(attempt);
                if (candidate != null && isProbablyText(candidate)) {
                    found.add(candidate);
                }
            }
            if (!found.isEmpty()) {
                break;
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        found.sort(Comparator
                .comparing((VirtualFile vf) -> !Objects.equals(ownerModule(project, vf), req.moduleName()))
                .thenComparing(SymbolSourceLookup::isJarFile)
                .thenComparing(SymbolSourceLookup::urlOf));
        VirtualFile primary = found.getFirst();
        Snippet snippet = buildSnippetForVirtualFile(project, primary, req.lineStart(), req.lineEnd());
        String message = found.size() > 1
                ? "Resolved resource; multiple copies on classpath. Returning best match and listing alternatives."
                : "Resolved resource successfully.";
        return new ResourceResult(primary, found, snippet, message, presentResourceKey(primary, attempts), null);
    }

    private static VirtualFile[] classRoots(Project project, @Nullable String moduleName) {
        if (moduleName != null && !moduleName.isBlank()) {
            Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
            if (module != null) {
                return ModuleRootManager.getInstance(module).orderEntries().classes().getRoots();
            }
        }
        return ProjectRootManager.getInstance(project).orderEntries().classes().getRoots();
    }

    private static boolean isProbablyText(VirtualFile vf) {
        FileType fileType = vf.getFileType();
        if (fileType.isBinary()) {
            return false;
        }
        String extension = vf.getExtension();
        return extension == null || TEXT_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    private static String presentResourceKey(VirtualFile vf, List<String> attempts) {
        return attempts.isEmpty() ? vf.getUrl() : attempts.getFirst();
    }

    private static String truncateByDepth(@Nullable String text, @Nullable Integer responseDepth) {
        if (text == null) {
            return null;
        }
        if (responseDepth == null) {
            return text;
        }
        int limit = Math.max(0, responseDepth);
        StringBuilder truncated = new StringBuilder(text.length());
        int currentDepth = 0;
        int skipDepth = 0;
        boolean truncatedOutput = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                if (skipDepth == 0) {
                    truncated.append(ch);
                }
                currentDepth++;
                if (currentDepth > limit) {
                    skipDepth++;
                    if (skipDepth == 1) {
                        truncatedOutput = true;
                        appendEllipsis(truncated, currentDepth);
                    }
                }
            } else if (ch == '}') {
                if (currentDepth > limit && skipDepth > 0) {
                    skipDepth--;
                }
                currentDepth = Math.max(0, currentDepth - 1);
                if (skipDepth == 0) {
                    truncated.append(ch);
                }
            } else {
                if (skipDepth == 0) {
                    truncated.append(ch);
                }
            }
        }
        return truncatedOutput ? truncated.toString() : text;
    }

    private static void appendEllipsis(StringBuilder builder, int depth) {
        if (builder.isEmpty() || builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        builder.append(indentation(depth));
        builder.append("...");
        builder.append('\n');
    }

    private static String indentation(int depth) {
        if (depth <= 0) {
            return "";
        }
        return "    ".repeat(depth);
    }

    private static Snippet buildSnippetForPsi(Project project, PsiFile psiFile, PsiElement element,
            @Nullable Integer lineStart, @Nullable Integer lineEnd) {
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document != null && lineStart != null && lineEnd != null) {
            return sliceByLines(document, lineStart, lineEnd);
        }
        String text = element.getText();
        TextRange range = element.getTextRange();
        int startOffset = range != null ? range.getStartOffset() : -1;
        int endOffset = range != null ? range.getEndOffset() : -1;
        int startLine = 0;
        int endLine = 0;
        if (document != null && range != null) {
            startLine = document.getLineNumber(startOffset) + 1;
            endLine = document.getLineNumber(Math.max(startOffset, endOffset - 1)) + 1;
        } else if (range != null) {
            String full = psiFile.getText();
            if (full != null) {
                startLine = 1 + countNewlines(full, 0, Math.max(0, startOffset));
                endLine = 1 + countNewlines(full, 0, Math.max(0, endOffset - 1));
            }
        }
        return new Snippet(text, startLine, endLine, startOffset, endOffset);
    }

    private static Snippet buildSnippetForVirtualFile(Project project, VirtualFile virtualFile,
            @Nullable Integer lineStart, @Nullable Integer lineEnd) {
        Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
        if (document != null) {
            if (lineStart != null && lineEnd != null) {
                return sliceByLines(document, lineStart, lineEnd);
            }
            String text = document.getText();
            return new Snippet(text, 0, 0, -1, -1);
        }
        String text;
        try {
            text = VfsUtilCore.loadText(virtualFile);
        } catch (IOException ex) {
            LOG.warn("Unable to load resource text", ex);
            text = "";
        }
        if (lineStart != null && lineEnd != null) {
            int[] offsets = offsetsForLineRange(text, lineStart, lineEnd);
            String slice = safeSubstr(text, offsets[0], offsets[1]);
            return new Snippet(slice, lineStart, lineEnd, offsets[0], offsets[1]);
        }
        return new Snippet(text, 0, 0, -1, -1);
    }

    private static @NotNull GlobalSearchScope scopeFor(@NotNull Project project, @Nullable String moduleName) {
        if (moduleName == null || moduleName.isBlank()) {
            return ProjectScope.getAllScope(project);
        }
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            return ProjectScope.getAllScope(project);
        }
        return GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true);
    }

    private static Snippet sliceByLines(Document document, int lineStart1, int lineEnd1) {
        int lineCount = document.getLineCount();
        int startLine = clamp(lineStart1, 1, lineCount);
        int endLine = clamp(lineEnd1, startLine, lineCount);
        int startOffset = document.getLineStartOffset(startLine - 1);
        int endOffset = document.getLineEndOffset(endLine - 1);
        String text = document.getText().substring(startOffset, endOffset);
        return new Snippet(text, startLine, endLine, startOffset, endOffset);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static boolean isCompiled(@Nullable PsiFile file) {
        return file instanceof PsiCompiledElement;
    }

    private static boolean isJarFile(@Nullable PsiFile file) {
        if (file == null) {
            return false;
        }
        VirtualFile vf = file.getVirtualFile();
        return isJarFile(vf);
    }

    private static boolean isJarFile(@Nullable VirtualFile virtualFile) {
        if (virtualFile == null) {
            return false;
        }
        String url = virtualFile.getUrl();
        return url.startsWith("jar://");
    }

    private static String fileUrlOf(PsiElement element) {
        PsiFile file = element.getContainingFile();
        VirtualFile vf = file != null ? file.getVirtualFile() : null;
        return vf != null ? vf.getUrl() : null;
    }

    private static String urlOf(@NotNull PsiFile file) {
        VirtualFile vf = file.getVirtualFile();
        return vf != null ? vf.getUrl() : "";
    }

    private static String urlOf(@NotNull VirtualFile file) {
        return file.getUrl();
    }

    private static PsiElement chooseElementForText(PsiElement target, boolean forceDecompiled, boolean preferSource) {
        if (forceDecompiled) {
            return target;
        }
        PsiElement navigation = target.getNavigationElement();
        if (preferSource && navigation != null) {
            return navigation;
        }
        return navigation != null ? navigation : target;
    }

    private static String qualifiedNameOf(PsiElement element) {
        if (element instanceof PsiClass psiClass) {
            String qn = psiClass.getQualifiedName();
            return qn != null ? qn : psiClass.getName();
        }
        if (element instanceof PsiMethod method) {
            PsiClass owner = method.getContainingClass();
            String ownerName = owner != null ? owner.getQualifiedName() : "<owner>";
            return ownerName + "#" + method.getName();
        }
        if (element instanceof PsiField field) {
            PsiClass owner = field.getContainingClass();
            String ownerName = owner != null ? owner.getQualifiedName() : "<owner>";
            return ownerName + "#" + field.getName();
        }
        PsiFile file = element.getContainingFile();
        VirtualFile vf = file != null ? file.getVirtualFile() : null;
        return vf != null ? vf.getUrl() : "<unknown>";
    }

    private static Candidate candidateOf(PsiElement element) {
        Project project = element.getProject();
        return candidateOf(project, element);
    }

    private static Candidate candidateOf(Project project, PsiElement element) {
        PsiFile file = element.getContainingFile();
        VirtualFile vf = file != null ? file.getVirtualFile() : null;
        String uri = vf != null ? vf.getUrl() : "";
        String module = ownerModule(project, element);
        boolean isDecompiled = file instanceof PsiCompiledElement || element instanceof PsiCompiledElement;
        String origin = isDecompiled ? "DECOMPILED" : "SOURCE";
        String classpath = classpathEntry(project, vf);
        return new Candidate(qualifiedNameOf(element), origin, module, classpath, uri, kindOf(element));
    }

    private static Candidate candidateOf(Project project, VirtualFile file) {
        String uri = file.getUrl();
        String module = ownerModule(project, file);
        String classpath = classpathEntry(project, file);
        return new Candidate(uri, "RESOURCE", module, classpath, uri, SymbolKind.RESOURCE);
    }

    private static SymbolKind kindOf(PsiElement element) {
        if (element instanceof PsiClass) {
            return SymbolKind.CLASS;
        }
        if (element instanceof PsiMethod) {
            return SymbolKind.METHOD;
        }
        if (element instanceof PsiField) {
            return SymbolKind.FIELD;
        }
        if (element instanceof PsiFile) {
            return SymbolKind.FILE;
        }
        return SymbolKind.UNKNOWN;
    }

    private static String ownerModule(Project project, PsiElement element) {
        PsiFile file = element.getContainingFile();
        VirtualFile vf = file != null ? file.getVirtualFile() : null;
        return ownerModule(project, vf);
    }

    private static String ownerModule(Project project, @Nullable VirtualFile file) {
        if (file == null) {
            return null;
        }
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Module module = index.getModuleForFile(file, false);
        return module != null ? module.getName() : null;
    }

    private static String classpathEntry(Project project, @Nullable VirtualFile file) {
        if (file == null) {
            return "";
        }
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        List<OrderEntry> entries = index.getOrderEntriesForFile(file);
        if (entries.isEmpty()) {
            return "";
        }
        return entries.stream().map(OrderEntry::getPresentableName).distinct().collect(Collectors.joining(" | "));
    }

    private static List<PsiMethod> filterByParamTypes(List<PsiMethod> methods, @Nullable List<String> typeNames) {
        if (typeNames == null || typeNames.isEmpty()) {
            return methods;
        }
        return methods.stream().filter(method -> sameErasure(method, typeNames)).collect(Collectors.toList());
    }

    private static boolean sameErasure(PsiMethod method, List<String> names) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length != names.size()) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            String expected = normalizeTypeName(names.get(i));
            String actual = normalizeTypeName(parameters[i].getType().getCanonicalText());
            if (!actual.equals(expected) && !shortName(actual).equals(shortName(expected))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeTypeName(String raw) {
        if (raw == null) {
            return "";
        }
        String noGenerics = raw.replaceAll("<.*>", "");
        return noGenerics.replace("...", "[]").trim();
    }

    private static String shortName(String text) {
        int idx = text.lastIndexOf('.');
        return idx >= 0 ? text.substring(idx + 1) : text;
    }

    private static Result notFoundInside(PsiClass owner, Request req, String message) {
        return new Result(
                Status.NOT_FOUND,
                message,
                null,
                null,
                owner.getQualifiedName(),
                SymbolKind.UNKNOWN,
                req.moduleName(),
                null,
                0,
                0,
                -1,
                -1,
                List.of(candidateOf(owner)),
                null);
    }

    private static Result problem(String message, Request req, PsiElement target, SymbolKind kind) {
        return new Result(
                Status.ERROR,
                message,
                null,
                null,
                qualifiedNameOf(target),
                kind,
                null,
                null,
                0,
                0,
                -1,
                -1,
                List.of(candidateOf(target)),
                null);
    }

    private static String signatureOf(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        String ownerName = owner != null ? owner.getQualifiedName() : "<owner>";
        String params = Arrays.stream(method.getParameterList().getParameters())
                .map(param -> param.getType().getCanonicalText())
                .collect(Collectors.joining(", "));
        return ownerName + "#" + method.getName() + "(" + params + ")";
    }

    private static String signatures(List<PsiMethod> methods) {
        return methods.stream().map(SymbolSourceLookup::signatureOf).collect(Collectors.joining("\n"));
    }

    private static List<Candidate> dedupeCandidatesAltFirst(Candidate primary, List<Candidate> rest) {
        LinkedHashMap<String, Candidate> map = new LinkedHashMap<>();
        map.put(primary.uri(), primary);
        for (Candidate candidate : rest) {
            map.putIfAbsent(candidate.uri(), candidate);
        }
        return new ArrayList<>(map.values());
    }

    private static String topStack(Throwable throwable) {
        StackTraceElement[] stack = throwable.getStackTrace();
        if (stack == null || stack.length == 0) {
            return "";
        }
        StackTraceElement top = stack[0];
        return top.getClassName() + ":" + top.getLineNumber();
    }

    private static Result indexingResult(String message, Request req) {
        return new Result(
                Status.INDEXING,
                message,
                null,
                null,
                req.symbolName(),
                SymbolKind.UNKNOWN,
                req.moduleName(),
                null,
                0,
                0,
                -1,
                -1,
                List.of(),
                "Dumb mode detected; not blocking for indices.");
    }

    private static String anchor(String baseUri, int startLine, int endLine, int startOffset, int endOffset) {
        if (baseUri == null || baseUri.isBlank()) {
            return baseUri;
        }
        if (startLine > 0) {
            if (endLine > startLine) {
                return baseUri + "#L" + startLine + "-L" + endLine;
            }
            return baseUri + "#L" + startLine;
        }
        if (startOffset >= 0) {
            if (endOffset > startOffset) {
                return baseUri + "#offset=" + startOffset + "-" + endOffset;
            }
            return baseUri + "#offset=" + startOffset;
        }
        return baseUri;
    }

    private static String extractShortName(String name) {
        String normalized = name;
        if (normalized.contains("/")) {
            normalized = lastSegment(normalized);
        }
        int idx = normalized.lastIndexOf('.');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private static String lastSegment(String path) {
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private static boolean hasExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 && idx < name.length() - 1;
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static int countNewlines(String text, int from, int toExclusive) {
        int count = 0;
        for (int i = from; i < toExclusive && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static int[] offsetsForLineRange(String text, int lineStart1, int lineEnd1) {
        int line = 1;
        int startOffset = 0;
        int index = 0;
        for (; index < text.length(); index++) {
            if (line == lineStart1) {
                startOffset = index;
                break;
            }
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        if (lineStart1 <= 1) {
            startOffset = 0;
        }
        int endOffset = text.length();
        for (; index < text.length(); index++) {
            if (line == lineEnd1 && (index + 1 == text.length() || text.charAt(index) == '\n')) {
                endOffset = text.charAt(index) == '\n' ? index : index + 1;
                break;
            }
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        return new int[] { startOffset, Math.max(startOffset, endOffset) };
    }

    private static String safeSubstr(String text, int start, int end) {
        int begin = Math.max(0, Math.min(start, text.length()));
        int finish = Math.max(begin, Math.min(end, text.length()));
        return text.substring(begin, finish);
    }

    private static final class Snippet {
        final String text;
        final int startLine;
        final int endLine;
        final int startOffset;
        final int endOffset;

        Snippet(String text, int startLine, int endLine, int startOffset, int endOffset) {
            this.text = text;
            this.startLine = startLine;
            this.endLine = endLine;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    private static final class ResourceResult {
        final VirtualFile primaryVf;
        final List<VirtualFile> alternatives;
        final Snippet snippet;
        final String message;
        final String symbolKey;
        final String diagnostics;

        ResourceResult(VirtualFile primaryVf, List<VirtualFile> alternatives, Snippet snippet, String message,
                String symbolKey, String diagnostics) {
            this.primaryVf = primaryVf;
            this.alternatives = alternatives;
            this.snippet = snippet;
            this.message = message;
            this.symbolKey = symbolKey;
            this.diagnostics = diagnostics;
        }
    }
}
