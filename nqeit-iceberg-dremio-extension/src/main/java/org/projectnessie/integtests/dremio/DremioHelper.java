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

public class DremioHelper {
  private String token;
  private String projectId;
  private String baseUrl;
  private String catalogName;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static JsonNode parseJson(String json) throws IOException {
    return OBJECT_MAPPER.readTree(json);
  }

  DremioHelper(String projectId, String token, String baseUrl, String catalogName) {
    this.projectId = projectId;
    this.token = token;
    this.baseUrl = baseUrl;
    this.catalogName = catalogName;
  }

  private String createPayload(String query) throws JsonProcessingException {
    Map<String, Object> payload = new HashMap<>();
    payload.put("sql", query);
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
    String jobState = "RUNNING";
    String url = baseUrl + "/v0/projects/" + projectId + "/job/" + jobId;
    Set<String> finalJobStates = new HashSet<>(asList("COMPLETED", "FAILED", "CANCELED"));
    // Default Timeout for engine-startup is 5min
    Duration timeout = Duration.ofMinutes(5);
    Instant deadline = Instant.now().plus(timeout);

    while (true) {
      String responseBody = performHttpRequest(url, null);
      JsonNode node = parseJson(responseBody);
      jobState = node.get("jobState").textValue();
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

  private List<List<Object>> parseQueryResult(String jobId) throws IOException {
    // The doc for getting the job results for cloud is not there, but it is similar to software
    // See docs: https://docs.dremio.com/software/rest-api/jobs/get-job/
    String url = baseUrl + "/v0/projects/" + projectId + "/job/" + jobId + "/results";
    String result = performHttpRequest(url, null);

    JsonNode node = parseJson(result);
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
    String url = baseUrl + "/v0/projects/" + projectId + "/sql";
    String result = performHttpRequest(url, payload);
    JsonNode node = parseJson(result);
    return node.get("id").textValue();
  }

  private String awaitSqlJobResult(String query) throws IOException {
    String jobId = submitQueryAndGetJobId(query);
    waitForJobCompletion(jobId, query);
    return jobId;
  }

  public List<List<Object>> runSelectQuery(String query) {
    try {
      String jobId = awaitSqlJobResult(query);
      return parseQueryResult(jobId);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void runQuery(String query) {
    try {
      awaitSqlJobResult(query);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
