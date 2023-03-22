/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.integtests.dremio;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.errorprone.annotations.FormatMethod;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.intellij.lang.annotations.Language;

public class DremioHelper {
  private String token;
  private String projectUrl;
  private String catalogName;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static JsonNode parseJson(String json, String url) {
    try {
      return OBJECT_MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to parse Response from " + url + " as JSON:\n" + json, e);
    }
  }

  DremioHelper(String token, String baseUrl, String projectId, String catalogName) {
    this.token = token;
    if (baseUrl.contains("://app.")) {
      baseUrl = baseUrl.replaceFirst("://app.", "://api.");
    }
    this.projectUrl = baseUrl + "/v0/projects/" + projectId;
    this.catalogName = catalogName;
  }

  public String getCatalogName() {
    return catalogName;
  }

  private String createPayload(String query) throws JsonProcessingException {
    Map<String, Object> payload = new HashMap<>();
    payload.put("sql", query);
    // TODO: for some reason this no longer works and we have to use fully qualified table names
    payload.put("context", asList(catalogName, "db"));
    return OBJECT_MAPPER.writeValueAsString(payload);
  }

  private String readResponse(HttpURLConnection con, String url) throws IOException {
    int responseCode = con.getResponseCode();
    if (responseCode != HttpURLConnection.HTTP_OK) { // success
      throw new IOException(
          "Request for " + url + " was not successful with http code: " + responseCode);
    }
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
      String inputLine;
      StringBuilder response = new StringBuilder();
      while ((inputLine = in.readLine()) != null) {
        response.append(inputLine);
      }
      return response.toString();
    }
  }

  private String performHttpRequest(String url, String payload) throws IOException {
    URL obj = new URL(url);
    HttpURLConnection con = (HttpURLConnection) obj.openConnection();
    con.setRequestProperty("Authorization", "Bearer " + token);
    con.setRequestProperty("Content-Type", "application/json");
    if (payload != null) {
      con.setRequestMethod("POST");
      con.setDoOutput(true);
      try (OutputStream os = con.getOutputStream()) {
        os.write(payload.getBytes(StandardCharsets.UTF_8));
      }
    } else {
      con.setRequestMethod("GET");
    }
    return readResponse(con, url);
  }

  private void waitForJobCompletion(String jobId, String query) throws IOException {
    // The doc for getting the job status for cloud is not there, but it is similar to software
    // See docs: https://docs.dremio.com/software/rest-api/jobs/get-job/
    String url = projectUrl + "/job/" + jobId;
    Set<String> finalJobStates = new HashSet<>(asList("COMPLETED", "FAILED", "CANCELED"));
    // Default Timeout for engine-startup is 5min
    Duration timeout = Duration.ofMinutes(5);
    Instant deadline = Instant.now().plus(timeout);

    while (true) {
      String responseBody = performHttpRequest(url, null);
      JsonNode node = parseJson(responseBody, url);
      String jobState = node.get("jobState").textValue();
      if (finalJobStates.contains(jobState)) {
        assertThat(jobState)
            .withFailMessage("jobId: %s\nQuery: %s\nresponse body: %s", jobId, query, responseBody)
            .isEqualTo("COMPLETED");
        return;
      }
      assertThat(Instant.now())
          .withFailMessage(
              "Timeout after %s\njobId: %s\nQuery: %s\nresponse body: %s",
              timeout, jobId, query, responseBody)
          .isBefore(deadline);
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private List<List<Object>> fetchQueryResult(String jobId) throws IOException {
    // See docs: https://docs.dremio.com/cloud/api/job/
    String url = projectUrl + "/job/" + jobId + "/results";
    String result = performHttpRequest(url, null);

    JsonNode node = parseJson(result, url);
    int noOfRows = node.get("rowCount").intValue();
    ArrayNode arrayNode = (ArrayNode) node.get("rows");
    List<List<Object>> list = new ArrayList<>();
    for (int i = 0; i < noOfRows; i++) {
      JsonNode row = arrayNode.get(i);
      list.add(asList(row.get("id").intValue(), row.get("val").textValue()));
    }
    return list;
  }

  private String submitQueryAndGetJobId(String query) throws IOException {
    // See docs: https://docs.dremio.com/cloud/api/sql/
    String payload = createPayload(query);
    String url = projectUrl + "/sql";
    String result = performHttpRequest(url, payload);
    JsonNode node = parseJson(result, url);
    JsonNode idNode = node.get("id");
    if (idNode == null) {
      throw new IOException("Failed to get job ID from response:\n" + result);
    }
    return idNode.textValue();
  }

  private String awaitSqlJobResult(String query) throws IOException {
    String jobId = submitQueryAndGetJobId(query);
    waitForJobCompletion(jobId, query);
    return jobId;
  }

  @FormatMethod
  public List<List<Object>> runSelectQuery(@Language("SQL") String query, Object... args) {
    String jobId = runQuery(query, args);
    try {
      return fetchQueryResult(jobId);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns the id of the finished dremio job. */
  @FormatMethod
  public String runQuery(@Language("SQL") String query, Object... args) {
    String fullQuery = String.format(query, args);
    try {
      return awaitSqlJobResult(fullQuery);
    } catch (Exception e) {
      throw new RuntimeException("Dremio failed to run SQL: " + fullQuery, e);
    }
  }
}
