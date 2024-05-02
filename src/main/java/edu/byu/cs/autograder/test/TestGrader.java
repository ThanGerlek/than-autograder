package edu.byu.cs.autograder.test;

import edu.byu.cs.autograder.GradingContext;
import edu.byu.cs.autograder.GradingException;
import edu.byu.cs.dataAccess.DaoService;
import edu.byu.cs.dataAccess.DataAccessException;
import edu.byu.cs.model.Rubric;
import edu.byu.cs.model.RubricConfig;
import edu.byu.cs.util.PhaseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Set;

public abstract class TestGrader {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestGrader.class);

    /**
     * The path where the official tests are stored
     */
    protected final File phaseTests;

    /**
     * The path where the compiled tests are stored (and ran)
     */
    private final File stageTestsPath;

    /**
     * The module to compile during this test
     */
    protected final String module;

    protected final GradingContext gradingContext;

    protected final TestHelper testHelper = new TestHelper();

    public TestGrader(GradingContext gradingContext) {
        this.gradingContext = gradingContext;
        this.stageTestsPath = new File(gradingContext.stagePath() + "/tests");
        this.phaseTests = new File("./phases/phase" + PhaseUtils.getPhaseAsString(gradingContext.phase()));
        this.module = PhaseUtils.getModuleUnderTest(gradingContext.phase());
    }


    public Rubric.Results runTests() throws GradingException, DataAccessException {
        compileTests();
        gradingContext.observer().update("Running " + name() + " tests...");

        TestAnalyzer.TestAnalysis results;
        if (!new File(gradingContext.stagePath(), "tests").exists()) {
            results = new TestAnalyzer.TestAnalysis(new TestAnalyzer.TestNode(), null);
            TestAnalyzer.TestNode.countTests(results.root());
        } else {
            results = new TestHelper().runJUnitTests(new File(gradingContext.stageRepo(),
                            "/" + module + "/target/" + module + "-test-dependencies.jar"), stageTestsPath,
                    packagesToTest(), extraCreditTests());
        }

        if (results.root() == null) {
            results = new TestAnalyzer.TestAnalysis(new TestAnalyzer.TestNode(), results.error());
            TestAnalyzer.TestNode.countTests(results.root());
            LOGGER.error("{} tests failed to run for {} in phase {}", name(), gradingContext.netId(),
                    PhaseUtils.getPhaseAsString(gradingContext.phase()));
        }

        results.root().setTestName(testName());

        float score = getScore(results);
        RubricConfig rubricConfig = DaoService.getRubricConfigDao().getRubricConfig(gradingContext.phase());

        return new Rubric.Results(getNotes(results), score, rubricConfigItem(rubricConfig).points(), results,
                null);
    }

    private void compileTests() throws GradingException {
        gradingContext.observer().update("Compiling " + name() + " tests...");
        testHelper.compileTests(gradingContext.stageRepo(), module, testsToCompile(), gradingContext.stagePath());
    }

    protected abstract String name();

    protected abstract Set<File> testsToCompile() throws GradingException;

    protected abstract Set<String> packagesToTest() throws GradingException;

    protected abstract Set<String> extraCreditTests() throws GradingException;

    protected abstract String testName();

    protected abstract float getScore(TestAnalyzer.TestAnalysis testResults) throws GradingException;

    protected abstract String getNotes(TestAnalyzer.TestAnalysis testResults) throws GradingException;

    protected abstract RubricConfig.RubricConfigItem rubricConfigItem(RubricConfig config);

}
