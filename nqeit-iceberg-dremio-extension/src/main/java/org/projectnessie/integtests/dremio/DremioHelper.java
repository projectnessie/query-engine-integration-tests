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

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

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

  private RequestSpecification rest() {
    return RestAssured.given()
        .baseUri(baseUrl)
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .basePath("v0/projects/" + projectId);
  }

  private String createPayload(String query) {
    String payload =
        format("{ \"sql\": \"%s\", \"context\": [\"%s\", \"db\"] }", query, catalogName);
    return payload;
  }

  private String waitForJobStatus(String jobId) {
    String jobState = "RUNNING";
    Set<String> finalJobStatesList = new HashSet<>(asList("COMPLETED", "FAILED", "CANCELED"));
    while (!finalJobStatesList.contains(jobState)) {
      String jsonString = rest().when().get("/job/" + jobId).getBody().asString();
      jobState = JsonPath.from(jsonString).get("jobState");
    }
    return jobState;
  }

  private List<List<Object>> parseQueryResult(String jobId) {

    String jsonString = rest().when().get("/job/" + jobId + "/results").getBody().asString();
    JsonPath path = JsonPath.from(jsonString);
    int noOfRows = path.get("rowCount");
    List<List<Object>> list = new ArrayList<>();
    for (int i = 0; i < noOfRows; i++) {
      int id = path.get(format("rows[%d].id", i));
      String val = path.get(format("rows[%d].val", i));
      list.add(asList(id, val));
    }
    return list;
  }

  private String submitQueryAndGetJobId(String query) {
    String payload = createPayload(query);
    String jobId = JsonPath.from(rest().body(payload).post("/sql").getBody().asString()).get("id");
    return jobId;
  }

  private String awaitSqlJobResult(String query) {
    String jobId = submitQueryAndGetJobId(query);
    assertThat("COMPLETED").isEqualTo(waitForJobStatus(jobId));
    return jobId;
  }

  public List<List<Object>> runSelectQuery(String query) {
    String jobId = awaitSqlJobResult(query);
    return parseQueryResult(jobId);
  }

  public void runInsertQuery(String query) {
    awaitSqlJobResult(query);
  }

  public void executeDmlStatement(String query) {
    awaitSqlJobResult(query);
  }
}
