package com.projectlightpath.fixture;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TransferResourceTest {
    @Test
    void followsTheFixturePathThroughCdiAndTargetServices() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"requestReference\":\"req-001\",\"user\":\"alice\"}")
                .when()
                .post("/transfers")
                .then()
                .statusCode(200)
                .body("requestReference", is("req-001"))
                .body("accepted", is(true))
                .body("path", is("resource->service->auth->target"));
    }
}
