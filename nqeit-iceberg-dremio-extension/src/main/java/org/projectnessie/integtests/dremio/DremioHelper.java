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

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.json.JSONArray;
import org.json.JSONObject;

public class DremioHelper {
  private String token;
  private String projectId;
  private String baseUrl;
  private String catalogName;

  DremioHelper(String projectId, String token, String baseUrl, String catalogName) {
    this.projectId = projectId;
    this.token = token;
    this.baseUrl = baseUrl;
    this.catalogName = catalogName;
  }

  private String createPayload(String query) {
    String payload =
        format("{ \"sql\": \"%s\", \"context\": [\"%s\", \"db\"] }", query, catalogName);
    return payload;
  }

  private String executeHttpRequest(HttpUriRequestBase req) {
    req.addHeader("Authorization", "Bearer " + token);
    req.addHeader("Content-Type", "application/json");
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      return httpClient.execute(req, response -> EntityUtils.toString(response.getEntity()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String executeHttpPost(String url, String payload) {
    HttpPost httpPost = new HttpPost(url);
    httpPost.setEntity(new StringEntity(payload));
    return executeHttpRequest(httpPost);
  }

  private String executeHttpGet(String url) {
    HttpGet httpGet = new HttpGet(url);
    return executeHttpRequest(httpGet);
  }

  private String waitForJobStatus(String jobId) {
    // The doc for getting the job status for cloud is not there, but it is similar to software
    // See docs: https://docs.dremio.com/software/rest-api/jobs/get-job/
    String jobState = "RUNNING";
    String url = baseUrl + "/v0/projects/" + projectId + "/job/" + jobId;
    Set<String> finalJobStatesList = new HashSet<>(asList("COMPLETED", "FAILED", "CANCELED"));
    while (!finalJobStatesList.contains(jobState)) {
      String result = executeHttpGet(url);
      JSONObject jsonObject = new JSONObject(result);
      jobState = jsonObject.getString("jobState");
    }
    return jobState;
  }

  private List<List<Object>> parseQueryResult(String jobId) {
    // The doc for getting the job results for cloud is not there, but it is similar to software
    // See docs: https://docs.dremio.com/software/rest-api/jobs/get-job/
    String url = baseUrl + "/v0/projects/" + projectId + "/job/" + jobId + "/results";
    String result = executeHttpGet(url);

    JSONObject jsonObject = new JSONObject(result);
    int noOfRows = jsonObject.getInt("rowCount");
    JSONArray tableData = jsonObject.getJSONArray("rows");
    List<List<Object>> list = new ArrayList<>();
    for (int i = 0; i < noOfRows; i++) {
      JSONObject row = tableData.getJSONObject(i);
      list.add(asList(row.getInt("id"), row.getString("val")));
    }
    return list;
  }

  private String submitQueryAndGetJobId(String query) {
    // See docs: https://docs.dremio.com/cloud/api/sql/
    String payload = createPayload(query);
    String url = baseUrl + "/v0/projects/" + projectId + "/sql";
    String result = executeHttpPost(url, payload);
    JSONObject jsonObject = new JSONObject(result);
    String jobId = jsonObject.getString("id");
    return jobId;
  }

  private String awaitSqlJobResult(String query) {
    String jobId = submitQueryAndGetJobId(query);
    String jobStatus = waitForJobStatus(jobId);
    assertThat("COMPLETED")
        .withFailMessage("Query: %s, jobId: %s, jobStatus: %s", query, jobId, jobStatus)
        .isEqualTo(jobStatus);
    return jobId;
  }

  public List<List<Object>> runSelectQuery(String query) {
    String jobId = awaitSqlJobResult(query);
    return parseQueryResult(jobId);
  }

  public void runQuery(String query) {
    awaitSqlJobResult(query);
  }
}
