package edu.byu.cs.autograder;

import edu.byu.cs.model.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public abstract class PassoffTestGrader extends Grader {

    private static final Logger LOGGER = LoggerFactory.getLogger(PassoffTestGrader.class);

    /**
     * The path where the official tests are stored
     */
    private final File phaseTests;

    /**
     * The path where the compiled tests are stored (and ran)
     */
    private final File stageTestsPath;

    /**
     * The names of the test files with extra credit tests (excluding .java)
     */
    protected Set<String> extraCreditTests = new HashSet<>();

    /**
     * The value (in percentage) of each extra credit category
     */
    protected float extraCreditValue = 0;

    /**
     * Creates a new grader for phase X
     *
     * @param phaseResources the path to the phase resources
     * @param netId          the netId of the student
     * @param repoUrl        the url of the student repo
     * @param observer       the observer to notify of updates
     * @param phase          the phase to grade
     * @throws IOException if an IO error occurs
     */
    public PassoffTestGrader(String phaseResources, String netId, String repoUrl, Observer observer, Phase phase) throws IOException {
        super(repoUrl, netId, observer, phase);
        this.stageTestsPath = new File(stagePath + "/tests");
        this.phaseTests = new File(phaseResources);
    }

    @Override
    protected void runCustomTests() {
        // no unit tests for this phase
    }

    @Override
    protected void compileTests() {
        observer.update("Compiling tests...");

        // Process cannot handle relative paths or wildcards,
        // so we need to only use absolute paths and find
        // to get the files

        // absolute path to student's chess jar
        String chessJarWithDeps;
        try {
            chessJarWithDeps = new File(stageRepoPath, "/shared/target/shared-jar-with-dependencies.jar")
                    .getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ProcessBuilder processBuilder =
                new ProcessBuilder()
                        .directory(phaseTests)
//                        .inheritIO() // TODO: implement better logging
                        .command("find",
                                "passoffTests",
                                "-name",
                                "*.java",
                                "-exec",
                                "javac",
                                "-d",
                                stagePath + "/tests",
                                "-cp",
                                ".:" + chessJarWithDeps + ":" + standaloneJunitJarPath + ":" + junitJupiterApiJarPath,
                                "{}",
                                ";");

        try {
            Process process = processBuilder.start();
            if (process.waitFor() != 0) {
                observer.notifyError("exited with non-zero exit code");
                LOGGER.error("exited with non-zero exit code");
                throw new RuntimeException("exited with non-zero exit code");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        observer.update("Successfully compiled tests");
    }

    @Override
    protected TestAnalyzer.TestNode runTests() {
        observer.update("Running tests...");

        // Process cannot handle relative paths or wildcards,
        // so we need to only use absolute paths and find
        // to get the files
        String chessJarWithDeps = new File(stageRepoPath, "shared/target/shared-jar-with-dependencies.jar").getAbsolutePath();

        ProcessBuilder processBuilder = new ProcessBuilder()
                .directory(stageTestsPath)
//              .inheritIO() // TODO: implement better logging
                .command("java",
                        "-jar",
                        standaloneJunitJarPath,
                        "--class-path", ".:" + chessJarWithDeps + ":" + junitJupiterApiJarPath,
                        "--scan-class-path",
                        "--details=testfeed");

        try {
            Process process = processBuilder.start();

            if (process.waitFor() != 0) {
//                throw new RuntimeException("exited with non-zero exit code");
            }

            String output = getOutputFromProcess(process);

            TestAnalyzer testAnalyzer = new TestAnalyzer();

            System.out.println(output);
            return testAnalyzer.parse(output.split("\n"), extraCreditTests);

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getOutputFromProcess(Process process) throws IOException {
        String output;

        InputStream is = process.getInputStream();
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader br = new BufferedReader(isr);
        {
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append('\n');
            }

            output = sb.toString();
        }
        return output;
    }

    @Override
    protected float getScore(TestAnalyzer.TestNode results) {
        if (results == null)
            return 0;

        int totalStandardTests = results.numTestsFailed + results.numTestsPassed;
        int totalECTests = results.numExtraCreditPassed + results.numExtraCreditFailed;

        if (totalStandardTests == 0)
            return 0;

        float score = (float) results.numTestsPassed / totalStandardTests;
        if (totalECTests == 0) return score;

        // extra credit calculation
        if (score < 1f) return score;
        Map<String, Float> ecScores = getECScores(results);
        for (String category : extraCreditTests) {
            if (ecScores.get(category) == 1f) {
                score += extraCreditValue;
            }
        }

        return score;
    }

    private Map<String, Float> getECScores(TestAnalyzer.TestNode results) {
        Map<String, Float> scores = new HashMap<>();

        Queue<TestAnalyzer.TestNode> unchecked = new PriorityQueue<>();
        unchecked.add(results);

        while (!unchecked.isEmpty()) {
            TestAnalyzer.TestNode node = unchecked.remove();
            for (TestAnalyzer.TestNode child : node.children.values()) {
                if (child.ecCategory != null) {
                    scores.put(child.ecCategory, (float) child.numExtraCreditPassed /
                            (child.numExtraCreditPassed + child.numExtraCreditFailed));
                    unchecked.remove(child);
                } else unchecked.add(child);
            }
        }

        return scores;
    }

    @Override
    protected String getNotes(TestAnalyzer.TestNode results) {
        if (results == null)
            return "No tests were run";

        if (results.numTestsFailed == 0)
            return "All tests passed";

        if (getScore(results) != 1)
            return "Some tests failed. You must pass all tests to pass off this phase";

        return results.toString();
    }
}
