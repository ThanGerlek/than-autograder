package edu.byu.cs.autograder.compile;

import edu.byu.cs.autograder.GradingContext;
import edu.byu.cs.autograder.GradingException;
import edu.byu.cs.util.FileUtils;
import edu.byu.cs.util.ProcessUtils;

import java.io.File;
import java.util.Collection;
import java.util.List;

public class CompileHelper {
    private final GradingContext gradingContext;

    public CompileHelper(GradingContext gradingContext) {this.gradingContext = gradingContext;}

    private final Collection<StudentCodeModifier> currentModifiers =
            List.of(new ProjectStructureVerifier(), new PomModifier(), new PassoffJarModifier());

    public void compile() throws GradingException {
        for(StudentCodeModifier modifier : currentModifiers) {
            modifier.modifyCode(gradingContext);
        }
        packageRepo();
    }


    /**
     * Packages the student repo into a jar
     */
    private void packageRepo() throws GradingException {
        gradingContext.observer().update("Packaging repo...");

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(gradingContext.stageRepo());
        processBuilder.command("mvn", "package", "-DskipTests");
        try {
            ProcessUtils.ProcessOutput output = ProcessUtils.runProcess(processBuilder, 90000); //90 seconds
            if (output.statusCode() != 0) {
                throw new GradingException("Failed to package repo: ", getMavenError(output.stdOut()));
            }
        } catch (ProcessUtils.ProcessException ex) {
            throw new GradingException("Failed to package repo", ex);
        }

        gradingContext.observer().update("Successfully packaged repo");
    }

    /**
     * Retrieves maven error output from maven package stdout
     *
     * @param output A string containing maven standard output
     * @return A string containing maven package error lines
     */
    private String getMavenError(String output) {
        StringBuilder builder = new StringBuilder();
        for (String line : output.split("\n")) {
            if (line.contains("[ERROR] -> [Help 1]")) {
                break;
            }

            if(line.contains("[ERROR]")) {
                String trimLine = line.replace(gradingContext.stageRepo().getAbsolutePath(), "");
                builder.append(trimLine).append("\n");
            }
        }
        return builder.toString();
    }
}
