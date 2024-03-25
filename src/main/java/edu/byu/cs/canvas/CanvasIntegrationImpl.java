package edu.byu.cs.canvas;

import com.google.gson.Gson;
import edu.byu.cs.controller.SubmissionController;
import edu.byu.cs.model.User;
import edu.byu.cs.properties.ApplicationProperties;
import org.eclipse.jgit.annotations.Nullable;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.util.*;

public class CanvasIntegrationImpl implements CanvasIntegration {

    private static final String CANVAS_HOST = "https://byu.instructure.com";

    private static final String AUTHORIZATION_HEADER = ApplicationProperties.canvasAPIToken();

    // FIXME: set this dynamically or pull from config
    private static final int COURSE_NUMBER = 24410;

    // FIXME: set this dynamically or pull from config
    private static final int GIT_REPO_ASSIGNMENT_NUMBER = 880442;

    // FIXME: set this dynamically or pull from config
    public static final Map<Integer, Integer> sectionIDs;

    static {
        sectionIDs = new HashMap<>();
        sectionIDs.put(1, 26512);
        sectionIDs.put(2, 26513);
        sectionIDs.put(3, 25972);
        sectionIDs.put(4, 25496);
        sectionIDs.put(5, 25971);
    }

    private record Enrollment(EnrollmentType type) {

    }

    private record CanvasUser(int id, String sortable_name, String login_id, Enrollment[] enrollments) {

    }

    private record CanvasSubmissionUser(String url, CanvasUser user) {

    }

    private record CanvasAssignment(ZonedDateTime due_at) {

    }

    public User getUser(String netId) throws CanvasException {
        CanvasUser[] users = makeCanvasRequest(
                "GET",
                "/courses/" + COURSE_NUMBER + "/search_users?search_term=" + netId + "&include[]=enrollments",
                null,
                CanvasUser[].class);

        for (CanvasUser user : users) {
            if (user.login_id().equalsIgnoreCase(netId)) {
                User.Role role;
                if (user.enrollments.length == 0) role = null;
                else role = switch (user.enrollments[0].type()) {
                    case StudentEnrollment -> User.Role.STUDENT;
                    case TeacherEnrollment, TaEnrollment -> User.Role.ADMIN;
                    case DesignerEnrollment, ObserverEnrollment ->
                            throw new CanvasException("Unsupported role: " + user.enrollments[0]);
                };

                String[] names = user.sortable_name().split(",");
                String firstName = ((names.length >= 2) ? names[1] : "").trim();
                String lastName = ((names.length >= 1) ? names[0] : "").trim();

                String repoUrl = (role == User.Role.STUDENT) ? getGitRepo(user.id()) : null;

                if (role == User.Role.STUDENT) {
                    try {
                        SubmissionController.getRemoteHeadHash(repoUrl);
                    } catch (RuntimeException e) {
                        throw new CanvasException("Invalid repo url. Please resubmit the GitHub Repository assignment on Canvas");
                    }
                }

                return new User(netId, user.id(), firstName, lastName, repoUrl, role);
            }
        }

        throw new CanvasException("User not found in Canvas: " + netId);
    }


    public Collection<User> getAllStudents() throws CanvasException {
        return getMultipleStudents("/courses/" + COURSE_NUMBER + "/assignments/" +
                GIT_REPO_ASSIGNMENT_NUMBER + "/submissions?include[]=user");
    }

    public Collection<User> getAllStudentsBySection(int sectionID) throws CanvasException {
        return getMultipleStudents("/sections/" + sectionID + "/assignments/" +
                GIT_REPO_ASSIGNMENT_NUMBER + "/submissions?include[]=user");
    }

    private static Collection<User> getMultipleStudents(String baseUrl) throws CanvasException {
        int pageIndex = 1;
        int batchSize = 100;
        Set<CanvasSubmissionUser> allSubmissions = new HashSet<>();
        Set<User> allStudents = new HashSet<>();

        while (batchSize == 100) {
            CanvasSubmissionUser[] batch = makeCanvasRequest(
                    "GET",
                    baseUrl + "&per_page=" + batchSize + "&page=" + pageIndex,
                    null,
                    CanvasSubmissionUser[].class);
            batchSize = batch.length;
            allSubmissions.addAll(Arrays.asList(batch));
            pageIndex++;
        }

        for (CanvasSubmissionUser sub : allSubmissions) {
            if (sub.url == null) continue;
            CanvasUser user = sub.user;
            String[] names = user.sortable_name().split(",");
            String firstName = ((names.length >= 2) ? names[1] : "").trim();
            String lastName = ((names.length >= 1) ? names[0] : "").trim();
            allStudents.add(new User(user.login_id, user.id, firstName, lastName, sub.url, User.Role.STUDENT));
        }

        return allStudents;
    }

    /**
     * Submits the given grade for the given assignment for the given user
     *
     * @param userId        The canvas user id of the user to submit the grade for
     * @param assignmentNum The assignment number to submit the grade for
     * @param grade         The grade to submit (this is the total points earned, not a percentage)
     * @param comment       The comment to submit on the assignment
     * @throws CanvasException If there is an error with Canvas
     */
    public void submitGrade(int userId, int assignmentNum, @Nullable Float grade, @Nullable String comment) throws CanvasException {
        if(grade == null && comment == null)
            throw new IllegalArgumentException("grade and comment should not both be null");
        StringBuilder path = new StringBuilder();
        path.append("/courses/").append(COURSE_NUMBER).append("/assignments/").append(assignmentNum)
                .append("/submissions/").append(userId).append("?");
        if(grade != null) path.append("submission[posted_grade]=").append(grade).append("&");
        if(comment != null) {
            String encodedComment = URLEncoder.encode(comment, Charset.defaultCharset());
            path.append("comment[text_comment]=").append(encodedComment);
        }
        else path.deleteCharAt(path.length() - 1);

        makeCanvasRequest(
                "PUT",
                path.toString(),
                null,
                null);
    }

    /**
     * Submits the given grade for the given assignment for the given user. Any grades or comments in the rubric not
     * included in the parameter maps are retrieved from the previous submission to prevent the loss of previous grades
     * and comments (The canvas API will set items not included to empty/black rather than grabbing the old data)
     *
     * @requires The maps passed in must support the putIfAbsent method (Map.of() does not)
     *
     * @param userId            The canvas user id of the user to submit the grade for
     * @param assignmentNum     The assignment number to submit the grade for
     * @param grades            A Map of rubric item id's to grades for that rubric item
     * @param rubricComments    A Map of rubric item id's to comments to put on that rubric item
     * @param assignmentComment A comment for the entire assignment, if necessary
     * @throws CanvasException If there is an error with Canvas
     */
    public void submitGrade(int userId, int assignmentNum, Map<String, Float> grades,
                            Map<String, String> rubricComments, String assignmentComment) throws CanvasException {
        CanvasSubmission submission = getSubmission(userId, assignmentNum);
        if(submission.rubric_assessment() != null) {
            for(Map.Entry<String, CanvasIntegrationImpl.RubricItem> entry : submission.rubric_assessment().items().entrySet()) {
                grades.putIfAbsent(entry.getKey(), entry.getValue().points());
                rubricComments.putIfAbsent(entry.getKey(), entry.getValue().comments());
            }
        }

        StringBuilder queryStringBuilder = new StringBuilder();
        for(String rubricId : grades.keySet()) {
            queryStringBuilder.append("&rubric_assessment[").append(rubricId).append("][points]=")
                    .append(grades.get(rubricId));
        }
        for(String rubricId : rubricComments.keySet()) {
            queryStringBuilder.append("&rubric_assessment[").append(rubricId).append("][comments]=")
                    .append(URLEncoder.encode(rubricComments.get(rubricId), Charset.defaultCharset()));
        }
        if(assignmentComment != null && !assignmentComment.isBlank()) {
            queryStringBuilder.append("&comment[text_comment]=")
                    .append(URLEncoder.encode(assignmentComment, Charset.defaultCharset()));
        }
        if(!queryStringBuilder.isEmpty() && queryStringBuilder.charAt(0) == '&') {
            queryStringBuilder.setCharAt(0, '?');
        }

        makeCanvasRequest(
                "PUT",
                "/courses/" + COURSE_NUMBER + "/assignments/" + assignmentNum + "/submissions/" + userId +
                        queryStringBuilder,
                null,
                null);
    }


    /**
     * Gets the submission details for a specific student's assignment
     *
     * @param userId            The canvas user id of the user to submit the grade for
     * @param assignmentNum     The assignment number to submit the grade for
     * @return                  Submission details for the assignment
     * @throws CanvasException  If there is an error with Canvas
     */
    public CanvasSubmission getSubmission(int userId, int assignmentNum) throws CanvasException {
        return makeCanvasRequest(
                "GET",
                "/courses/" + COURSE_NUMBER + "/assignments/" + assignmentNum + "/submissions/" + userId + "?include[]=rubric_assessment",
                null,
                CanvasSubmission.class
        );
    }

    /**
     * Gets the git repository url for the given user from their GitHub Repository assignment submission on canvas
     *
     * @param userId The canvas user id of the user to get the git repository url for
     * @return The git repository url for the given user
     * @throws CanvasException If there is an error with Canvas
     */
    public String getGitRepo(int userId) throws CanvasException {
        CanvasSubmission submission = getSubmission(userId, GIT_REPO_ASSIGNMENT_NUMBER);

        if (submission == null)
            throw new CanvasException("Error while accessing GitHub Repository assignment submission on canvas");

        if (submission.url() == null)
            throw new CanvasException(
                    "The Github Repository assignment submission on Canvas must be submitted before accessing the autograder"
            );

        return submission.url();
    }


    public User getTestStudent() throws CanvasException {
        String testStudentName = "Test%20Student";

        CanvasUser[] users = makeCanvasRequest(
                "GET",
                "/courses/" + COURSE_NUMBER + "/search_users?search_term=" + testStudentName + "&include[]=test_student",
                null,
                CanvasUser[].class);

        if (users.length == 0)
            throw new CanvasException("Test Student not found in Canvas");

        String repoUrl = getGitRepo(users[0].id());

        if (repoUrl == null)
            throw new CanvasException("Test Student has not submitted the GitHub Repository assignment on Canvas");

        return new User(
                "test",
                users[0].id(),
                "Test",
                "Student",
                repoUrl,
                User.Role.STUDENT
        );
    }

    public ZonedDateTime getAssignmentDueDateForStudent(int userId, int assignmentId) throws CanvasException {
        CanvasAssignment assignment = makeCanvasRequest(
                "GET",
                "/users/" + userId + "/courses/" + COURSE_NUMBER + "/assignments?assignment_ids[]=" + assignmentId,
                null,
                CanvasAssignment[].class
        )[0];

        if (assignment == null || assignment.due_at() == null)
            throw new CanvasException("Unable to get due date for assignment");

        return assignment.due_at();
    }

    private enum EnrollmentType {
        StudentEnrollment, TeacherEnrollment, TaEnrollment, DesignerEnrollment, ObserverEnrollment

    }

    /**
     * Sends a request to canvas and returns the requested response
     *
     * @param method        The request method to use (e.g. "GET", "PUT", etc.)
     * @param path          The path to the endpoint to use (e.g. "/courses/12345")
     * @param request       The request body to send (or null if there is no request body)
     * @param responseClass The class of the response to return (or null if there is no response body)
     * @param <T>           The type of the response to return
     * @return The response from canvas
     * @throws CanvasException If there is an error while contacting canvas
     */
    private static <T> T makeCanvasRequest(String method, String path, Object request, Class<T> responseClass) throws CanvasException {
        try {
            URL url = new URI(CANVAS_HOST + "/api/v1" + path).toURL();
            HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
            https.setRequestMethod(method);
            https.addRequestProperty("Accept", "*/*");
            https.addRequestProperty("Accept-Encoding", "deflate");
            https.addRequestProperty("Authorization", AUTHORIZATION_HEADER);

            if (method.equals("POST") || method.equals("PUT"))
                https.setDoOutput(true);

            if (request != null) {
                https.addRequestProperty("Content-Type", "application/json");
                String reqData = new Gson().toJson(request);
                try (OutputStream reqBody = https.getOutputStream()) {
                    reqBody.write(reqData.getBytes());
                }
            }

            https.connect();

            if (https.getResponseCode() < 200 || https.getResponseCode() >= 300) {
                throw new CanvasException("Response from canvas wasn't 2xx, was " + https.getResponseCode());
            }

            return readBody(https, responseClass);
        } catch (Exception ex) {
            throw new CanvasException("Exception while contacting canvas", ex);
        }
    }

    /**
     * Reads the body of the response from canvas
     *
     * @param https         The connection to read the body from
     * @param responseClass The class of the response to return
     * @param <T>           The type of the response to return
     * @return The response from canvas
     * @throws IOException If there is an error reading the response from canvas
     */
    private static <T> T readBody(HttpsURLConnection https, Class<T> responseClass) throws IOException {
        if (https.getContentLength() < 0) {
            try (InputStream respBody = https.getInputStream()) {
                InputStreamReader reader = new InputStreamReader(respBody);
                if (responseClass != null) {
                    return new CanvasDeserializer<T>().deserialize(reader, responseClass);
                }
            }
        }
        return null;
    }

}
