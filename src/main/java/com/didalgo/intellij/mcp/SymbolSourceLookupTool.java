package com.didalgo.intellij.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;

public class SymbolSourceLookupTool {

    public SymbolSourceLookup.Result resolveSymbol(SymbolLookupInput input) {
        if (input == null) {
            return missingInputResult("`input` payload is required.");
        }
        if (input.symbolName == null || input.symbolName.isBlank()) {
            return missingInputResult("`symbolName` is required.");
        }
        Project project = resolveProject(input);
        if (project == null) {
            return new SymbolSourceLookup.Result(
                    SymbolSourceLookup.Status.NOT_FOUND,
                    "Unable to locate an open IntelliJ project for the request.",
                    null,
                    null,
                    input.symbolName,
                    SymbolSourceLookup.SymbolKind.UNKNOWN,
                    input.moduleName,
                    null,
                    0,
                    0,
                    -1,
                    -1,
                    List.of(),
                    "Specify projectName or open a single project.");
        }
        SymbolSourceLookup.Request request = new SymbolSourceLookup.Request(
                input.symbolName,
                input.methodName,
                input.methodParamTypes,
                input.fieldName,
                input.moduleName,
                input.lineStart,
                input.lineEnd,
                Objects.requireNonNullElse(input.preferSource, Boolean.TRUE),
                Objects.requireNonNullElse(input.includeInherited, Boolean.TRUE),
                Objects.requireNonNullElse(input.forceDecompiled, Boolean.FALSE),
                Objects.requireNonNullElse(input.allowResourceLookup, Boolean.TRUE));
        return SymbolSourceLookup.resolve(project, request);
    }

    private SymbolSourceLookup.Result missingInputResult(String message) {
        return new SymbolSourceLookup.Result(
                SymbolSourceLookup.Status.ERROR,
                message,
                null,
                null,
                null,
                SymbolSourceLookup.SymbolKind.UNKNOWN,
                null,
                null,
                0,
                0,
                -1,
                -1,
                List.of(),
                null);
    }

    private @Nullable Project resolveProject(SymbolLookupInput input) {
        if (input.projectName != null && !input.projectName.isBlank()) {
            Project byName = findProjectByNameOrPath(input.projectName);
            if (byName != null) {
                return byName;
            }
        }
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length == 1) {
            return openProjects[0];
        }
        if (input.projectRoot != null && !input.projectRoot.isBlank()) {
            Project byRoot = findProjectByRoot(input.projectRoot);
            if (byRoot != null) {
                return byRoot;
            }
        }
        return null;
    }

    private @Nullable Project findProjectByNameOrPath(String identifier) {
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        for (Project project : openProjects) {
            if (identifier.equals(project.getName())) {
                return project;
            }
            String basePath = project.getBasePath();
            if (basePath != null && Path.of(basePath).toAbsolutePath().normalize().toString()
                    .equals(Path.of(identifier).toAbsolutePath().normalize().toString())) {
                return project;
            }
        }
        return null;
    }

    private @Nullable Project findProjectByRoot(String rootPath) {
        Path root = Path.of(rootPath);
        VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(root);
        if (vf == null) {
            vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root);
        }
        if (vf == null) {
            return null;
        }
        Project project = ProjectLocator.getInstance().guessProjectForFile(vf);
        if (project != null) {
            return project;
        }
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        for (Project candidate : openProjects) {
            String base = candidate.getBasePath();
            if (base != null && Path.of(base).toAbsolutePath().normalize()
                    .equals(root.toAbsolutePath().normalize())) {
                return candidate;
            }
        }
        return null;
    }

    public static final class SymbolLookupInput {
        public String projectName;
        public String projectRoot;
        public String symbolName;
        public String methodName;
        public List<String> methodParamTypes;
        public String fieldName;
        public String moduleName;
        public Integer lineStart;
        public Integer lineEnd;
        public Boolean preferSource;
        public Boolean includeInherited;
        public Boolean forceDecompiled;
        public Boolean allowResourceLookup;
    }
}
