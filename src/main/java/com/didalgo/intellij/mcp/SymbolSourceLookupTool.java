package com.didalgo.intellij.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.springframework.ai.tool.annotation.ToolParam;

public class SymbolSourceLookupTool {

    public SymbolSourceLookup.Result resolveSymbol(SymbolLookupInput input) {
        if (input == null)
            return missingInputResult("`input` payload is required.");
        if (input.symbolName() == null || input.symbolName().isBlank())
            return missingInputResult("`symbolName` is required.");

        var project = resolveProject(input);
        if (project == null) {
            return new SymbolSourceLookup.Result(
                    SymbolSourceLookup.Status.NOT_FOUND,
                    "Unable to locate an open IntelliJ project for the request.",
                    null,
                    null,
                    input.symbolName(),
                    SymbolSourceLookup.SymbolKind.UNKNOWN,
                    input.moduleName(),
                    null,
                    0,
                    0,
                    -1,
                    -1,
                    List.of(),
                    "Specify projectName or open a single project.");
        }
        SymbolSourceLookup.Request request = new SymbolSourceLookup.Request(
                input.symbolName(),
                input.methodName(),
                input.methodParamTypes(),
                input.fieldName(),
                input.moduleName(),
                input.lineStart(),
                input.lineEnd(),
                true,
                true,
                false,
                true,
                input.responseDepth());
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
        if (input.projectPath() != null && !input.projectPath().isBlank()) {
            Project project = findProjectByPath(input.projectPath());
            if (project != null)
                return project;
        }

        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length == 1)
            return openProjects[0];

        return null;
    }

    private @Nullable Project findProjectByPath(String projectPath) {
        Path root = Path.of(projectPath);
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

    public record SymbolLookupInput(
            @ToolParam(description = "The project path. Always provide this value if known to reduce" +
                    " ambiguous calls. If only the current working directory is known, you can use it" +
                    " as the project path.", required = false)
            String projectPath,
            @ToolParam(description = "FQN or short name of an entity whose source code is requested")
            String symbolName,
            @ToolParam(required = false)
            String methodName,
            @ToolParam(required = false)
            List<String> methodParamTypes,
            @ToolParam(required = false)
            String fieldName,
            @ToolParam(required = false)
            String moduleName,
            @ToolParam(description = "Optional 1-based start line number", required = false)
            Integer lineStart,
            @ToolParam(description = "Optional 1-based final line number", required = false)
            Integer lineEnd,
            @ToolParam(description = "Optional depth limiting nested `{` `}` blocks; deeper levels are" +
                    " replaced with ellipses.", required = false)
            Integer responseDepth) { }
}
