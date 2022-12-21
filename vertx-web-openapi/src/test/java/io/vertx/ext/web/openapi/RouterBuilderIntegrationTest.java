package io.vertx.ext.web.openapi;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.multipart.MultipartForm;
import io.vertx.ext.web.validation.BodyProcessorException;
import io.vertx.ext.web.validation.ParameterProcessorException;
import io.vertx.ext.web.validation.RequestParameter;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.impl.ParameterLocation;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.vertx.ext.web.validation.testutils.TestRequest.*;
import static io.vertx.ext.web.validation.testutils.ValidationTestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This tests are about RouterBuilder behaviours
 *
 * @author Francesco Guardiani @slinkydeveloper
 */
@SuppressWarnings("unchecked")
@ExtendWith(VertxExtension.class)
@Timeout(1000)
public class RouterBuilderIntegrationTest extends BaseRouterBuilderTest {

  public static final String VALIDATION_SPEC = "src/test/resources/specs/validation_test.yaml";
  private final RouterBuilderOptions HANDLERS_TESTS_OPTIONS = new RouterBuilderOptions()
    .setMountNotImplementedHandler(false)
    .setRequireSecurityHandlers(false);

  private Future<Void> startFileServer(Vertx vertx, VertxTestContext testContext) {
    Router router = Router.router(vertx);
    router.route().handler(StaticHandler.create("src/test/resources"));
    return testContext.assertComplete(
      vertx.createHttpServer()
        .requestHandler(router)
        .listen(9001)
        .mapEmpty()
    );
  }

  private Future<Void> startSecuredFileServer(Vertx vertx, VertxTestContext testContext) {
    Router router = Router.router(vertx);
    router.route()
      .handler((RoutingContext ctx) -> {
        if (ctx.request().getHeader("Authorization") == null) ctx.fail(HttpResponseStatus.FORBIDDEN.code());
        else ctx.next();
      })
      .handler(StaticHandler.create("src/test/resources"));
    return testContext.assertComplete(
      vertx.createHttpServer()
        .requestHandler(router)
        .listen(9001)
        .mapEmpty()
    );
  }

  @Test
  public void loadSpecFromFile(Vertx vertx, VertxTestContext testContext) {
    RouterBuilder.create(vertx, "src/test/resources/specs/router_builder_test.yaml",
      routerBuilderAsyncResult -> {
        assertThat(routerBuilderAsyncResult.succeeded()).isTrue();
        assertThat(routerBuilderAsyncResult.result()).isNotNull();
        testContext.completeNow();
      });
  }

  @Test
  public void loadPetStoreFromFile(Vertx vertx, VertxTestContext testContext) {
    RouterBuilder.create(vertx, "src/test/resources/specs/petstore.yaml",
      routerBuilderAsyncResult -> {
        assertThat(routerBuilderAsyncResult.succeeded()).isTrue();
        assertThat(routerBuilderAsyncResult.result()).isNotNull();
        testContext.completeNow();
      });
  }

  @Test
  public void failLoadSpecFromFile(Vertx vertx, VertxTestContext testContext) {
    RouterBuilder.create(vertx, "src/test/resources/specs/aaa.yaml",
      routerBuilderAsyncResult -> {
        assertThat(routerBuilderAsyncResult.failed()).isTrue();
        assertThat(routerBuilderAsyncResult.cause().getClass())
          .isEqualTo(RouterBuilderException.class);
        assertThat(((RouterBuilderException) routerBuilderAsyncResult.cause()).type())
          .isEqualTo(ErrorType.INVALID_FILE);
        testContext.completeNow();
      });
  }

  @Test
  public void loadWrongSpecFromFile(Vertx vertx, VertxTestContext testContext) {
    RouterBuilder.create(vertx, "src/test/resources/specs/bad_spec.yaml",
      routerBuilderAsyncResult -> {
        assertThat(routerBuilderAsyncResult.failed()).isTrue();
        assertThat(routerBuilderAsyncResult.cause().getClass())
          .isEqualTo(RouterBuilderException.class);
        assertThat(((RouterBuilderException) routerBuilderAsyncResult.cause()).type())
          .isEqualTo(ErrorType.INVALID_FILE);
        testContext.completeNow();
      });
  }

  @Test
  public void loadSpecFromURL(Vertx vertx, VertxTestContext testContext) {
    startFileServer(vertx, testContext).onComplete(h -> {
      RouterBuilder.create(vertx, "http://localhost:9001/specs/router_builder_test.yaml",
        routerBuilderAsyncResult -> {
          testContext.verify(() -> {
            assertThat(routerBuilderAsyncResult.cause())
              .isNull();
            assertThat(routerBuilderAsyncResult.succeeded())
              .isTrue();
            assertThat(routerBuilderAsyncResult.result())
              .isNotNull();
          });
          testContext.completeNow();
        });
    });
  }

  @Test
  public void bodyHandlerNull(Vertx vertx, VertxTestContext testContext) {
    RouterBuilder.create(vertx, "src/test/resources/specs/router_builder_test.yaml",
      routerBuilderAsyncResult -> {
        assertThat(routerBuilderAsyncResult.succeeded()).isTrue();

        RouterBuilder routerBuilder = routerBuilderAsyncResult.result();
        routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);

        Router router = routerBuilder.createRouter();

        testContext.verify(() -> {
          assertThat(router.getRoutes())
            .extracting("state")
            .extracting("contextHandlers")
            .asList()
            .doesNotHave(new Condition<>(o -> o instanceof BodyHandler, "Handler is a BodyHandler"));
        });
        testContext.completeNow();
      });
  }

  @Test
  public void loadSpecFromURLWithAuthorizationValues(Vertx vertx, VertxTestContext testContext) {
    startSecuredFileServer(vertx, testContext).onComplete(h -> {
      RouterBuilder.create(
        vertx,
        "http://localhost:9001/specs/router_builder_test.yaml",
        new OpenAPILoaderOptions()
          .putAuthHeader("Authorization", "Bearer xx.yy.zz"),
        routerBuilderAsyncResult -> {
          assertThat(routerBuilderAsyncResult.succeeded()).isTrue();
          assertThat(routerBuilderAsyncResult.result()).isNotNull();
          testContext.completeNow();
        });
    });
  }

  @Test
  public void failLoadSpecFromURL(Vertx vertx, VertxTestContext testContext) {
    startFileServer(vertx, testContext).onComplete(h -> {
      RouterBuilder.create(vertx, "http://localhost:9001/specs/does_not_exist.yaml",
        routerBuilderAsyncResult -> {
          assertThat(routerBuilderAsyncResult.failed()).isTrue();
          assertThat(routerBuilderAsyncResult.cause().getClass()).isEqualTo(RouterBuilderException.class);
          assertThat(((RouterBuilderException) routerBuilderAsyncResult.cause()).type()).isEqualTo(ErrorType.INVALID_FILE);
          testContext.completeNow();
        });
    });
  }

  @Test
  public void mountHandlerTest(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint();
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);

        routerBuilder.operation("listPets").handler(routingContext ->
          routingContext
            .response()
            .setStatusCode(200)
            .end()
        );
      }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(200))
        .send(testContext, checkpoint)
    );
  }

  @Test
  public void mountFailureHandlerTest(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint();
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);

        routerBuilder
          .operation("listPets")
          .handler(routingContext -> routingContext.fail(null))
          .failureHandler(routingContext -> routingContext
            .response()
            .setStatusCode(500)
            .setStatusMessage("ERROR")
            .end()
          );
      }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(500), statusMessage("ERROR"))
        .send(testContext, checkpoint)
    );
  }

  @Test
  public void mountMultipleHandlers(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint();
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);

        routerBuilder
          .operation("listPets")
          .handler(routingContext ->
            routingContext.put("message", "A").next()
          )
          .handler(routingContext -> {
            routingContext.put("message", routingContext.get("message") + "B");
            routingContext.fail(500);
          });
        routerBuilder
          .operation("listPets")
          .failureHandler(routingContext ->
            routingContext.put("message", routingContext.get("message") + "E").next()
          )
          .failureHandler(routingContext ->
            routingContext
              .response()
              .setStatusCode(500)
              .setStatusMessage(routingContext.get("message"))
              .end()
          );
      }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(500), statusMessage("ABE"))
        .send(testContext, checkpoint)
    );
  }

  @Test
  public void concretePathsMatchFirst(Vertx vertx, VertxTestContext testContext) {
    // make sure /pets/mine is matched first when there is a choice between /pets/{petId} and /pets/mine
    // see https://github.com/OAI/OpenAPI-Specification/blob/main/versions/3.1.0.md#patterned-fields
    // also test situations (working in Vert.x 3) not strictly defined by the spec but agreed by one OpenAPI top contributor:
    // https://stackoverflow.com/questions/67382759/are-those-openapi-3-paths-ambiguous
    Checkpoint checkpoint = testContext.checkpoint(4);
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/path_matching_order.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);

        routerBuilder
          .operation("searchPets")
          .handler(routingContext -> {
            routingContext.response().setStatusMessage("searchPets").end();
          });
        routerBuilder
          .operation("searchPetsInShop")
          .handler(routingContext -> {
            routingContext.response().setStatusMessage("searchPetsInShop").end();
          });
        routerBuilder
          .operation("addPet")
          .handler(routingContext -> {
            routingContext.response().setStatusMessage("addPet").end();
          });
        routerBuilder
          .operation("addPetToShop")
          .handler(routingContext -> {
            routingContext.response().setStatusMessage("addPetToShop").end();
          });
      }).onComplete(h -> {
      testRequest(client, HttpMethod.POST, "/pets/wolfie")
        .expect(statusCode(200), statusMessage("addPet"))
        .sendJson(new JsonObject(), testContext, checkpoint);

      testRequest(client, HttpMethod.POST, "/pets/_search")
        .expect(statusCode(200), statusMessage("searchPets"))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.POST, "/shops/mine/pets/wolfie")
        .expect(statusCode(200), statusMessage("addPetToShop"))
        .sendJson(new JsonObject(), testContext, checkpoint);

      testRequest(client, HttpMethod.POST, "/shops/mine/pets/_search")
        .expect(statusCode(200), statusMessage("searchPetsInShop"))
        .send(testContext, checkpoint);

    });
  }

  @Test
  public void mountNotImplementedHandler(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint();
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(
          new RouterBuilderOptions()
            .setRequireSecurityHandlers(false)
            .setMountNotImplementedHandler(true)
        );
        routerBuilder.operation("showPetById").handler(RoutingContext::next);
      }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(501), statusMessage("Not Implemented"))
        .send(testContext, checkpoint)
    );
  }

  @Test
  public void mountNotAllowedHandler(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint();

    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(
          new RouterBuilderOptions()
            .setRequireSecurityHandlers(false)
            .setMountNotImplementedHandler(true)
        );

        routerBuilder.operation("deletePets").handler(RoutingContext::next);
        routerBuilder.operation("createPets").handler(RoutingContext::next);
      }).onComplete(rc ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(405), statusMessage("Method Not Allowed"))
        .expect(resp ->
          assertThat(new HashSet<>(Arrays.asList(resp.getHeader("Allow").split(Pattern.quote(", ")))))
            .isEqualTo(Stream.of("DELETE", "POST").collect(Collectors.toSet()))
        ).send(testContext, checkpoint)
    );
  }

  @Test
  public void addGlobalHandlersTest(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false));

        routerBuilder.rootHandler(rc -> {
          rc.response().putHeader("header-from-global-handler", "some dummy data");
          rc.next();
        }).rootHandler(rc -> {
          rc.response().putHeader("header-from-global-handler", "some more dummy data");
          rc.next();
        });

        routerBuilder.operation("listPets").handler(routingContext -> routingContext
          .response()
          .setStatusCode(200)
          .setStatusMessage("OK")
          .end()
        );
      }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(200))
        .expect(responseHeader("header-from-global-handler", "some more dummy data"))
        .send(testContext)
    );
  }

  @Test
  public void exposeConfigurationTest(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint();

    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false).setOperationModelKey(
          "fooBarKey"));

        routerBuilder.operation("listPets").handler(routingContext -> {
          JsonObject operation = routingContext.get("fooBarKey");

          routingContext
            .response()
            .setStatusCode(200)
            .setStatusMessage(operation.getString("operationId"))
            .end();
        });
      }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(200), statusMessage("listPets"))
        .send(testContext, checkpoint)
    );
  }

  @Test
  public void consumesTest(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(4);

    loadBuilderAndStartServer(vertx, "src/test/resources/specs/produces_consumes_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(new RouterBuilderOptions().setMountNotImplementedHandler(false));

        routerBuilder.operation("consumesTest").handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          if (params.body() != null && params.body().isJsonObject()) {
            routingContext
              .response()
              .setStatusCode(200)
              .putHeader("Content-Type", "application/json")
              .end(params.body().getJsonObject().encode());
          } else {
            routingContext
              .response()
              .setStatusCode(200)
              .end();
          }
        });
      }).onComplete(h -> {
      JsonObject obj = new JsonObject().put("name", "francesco");
      testRequest(client, HttpMethod.POST, "/consumesTest")
        .expect(statusCode(200))
        .expect(jsonBodyResponse(obj))
        .sendJson(obj, testContext, checkpoint);

      MultiMap form = MultiMap.caseInsensitiveMultiMap();
      form.add("name", "francesco");
      testRequest(client, HttpMethod.POST, "/consumesTest")
        .expect(statusCode(200))
        .expect(jsonBodyResponse(obj))
        .sendURLEncodedForm(form, testContext, checkpoint);

      MultipartForm multipartForm = MultipartForm.create();
      form.add("name", "francesco");
      testRequest(client, HttpMethod.POST, "/consumesTest")
        .expect(statusCode(400))
        .expect(badBodyResponse(BodyProcessorException.BodyProcessorErrorType.MISSING_MATCHING_BODY_PROCESSOR))
        .sendMultipartForm(multipartForm, testContext, checkpoint);

      testRequest(client, HttpMethod.POST, "/consumesTest")
        .expect(statusCode(400))
        .expect(failurePredicateResponse())
        .send(testContext, checkpoint);
    });
  }

  @Test
  public void producesTest(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);

    loadBuilderAndStartServer(vertx, "src/test/resources/specs/produces_consumes_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(new RouterBuilderOptions().setMountNotImplementedHandler(false));

        routerBuilder.operation("producesTest").handler(routingContext -> {
          if (((RequestParameters) routingContext.get("parsedParameters")).queryParameter("fail").getBoolean())
            routingContext
              .response()
              .putHeader("content-type", "text/plain")
              .setStatusCode(500)
              .end("Hate it");
          else
            routingContext.response().setStatusCode(200).end("{}"); // ResponseContentTypeHandler does the job for me
        });
      }).onComplete(h -> {
      String acceptableContentTypes = String.join(", ", "application/json", "text/plain");
      testRequest(client, HttpMethod.GET, "/producesTest")
        .with(requestHeader("Accept", acceptableContentTypes))
        .expect(statusCode(200), responseHeader("Content-type", "application/json"))
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/producesTest?fail=true")
        .with(requestHeader("Accept", acceptableContentTypes))
        .expect(statusCode(500), responseHeader("Content-type", "text/plain"))
        .send(testContext, checkpoint);
    });
  }

  @Test
  public void mountHandlersOrderTest(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);

    loadBuilderAndStartServer(vertx, "src/test/resources/specs/test_order_spec.yaml", testContext, routerBuilder -> {
      routerBuilder.setOptions(new RouterBuilderOptions().setMountNotImplementedHandler(false));

      routerBuilder.operation("showSpecialProduct").handler(routingContext ->
        routingContext.response().setStatusMessage("special").end()
      );

      routerBuilder.operation("showProductById").handler(routingContext -> {
        RequestParameters params = routingContext.get("parsedParameters");
        routingContext.response().setStatusMessage(params.pathParameter("id").getInteger().toString()).end();
      });

      testContext.completeNow();
    }).onComplete(h -> {
      testRequest(client, HttpMethod.GET, "/product/special")
        .expect(statusCode(200), statusMessage("special"))
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/product/123")
        .expect(statusCode(200), statusMessage("123"))
        .send(testContext, checkpoint);
    });
  }

  @Test
  public void mountHandlerEncodedTest(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint();

    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);

        routerBuilder.operation("encodedParamTest").handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          assertThat(params.pathParameter("p1").toString()).isEqualTo("a:b");
          assertThat(params.queryParameter("p2").toString()).isEqualTo("a:b");
          routingContext
            .response()
            .setStatusCode(200)
            .setStatusMessage(params.pathParameter("p1").toString())
            .end();
        });

        testContext.completeNow();
      }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/foo/a%3Ab?p2=a%3Ab")
        .expect(statusCode(200), statusMessage("a:b"))
        .send(testContext, checkpoint)
    );
  }

  /**
   * Tests that user can supply customised BodyHandler
   *
   * @throws Exception
   */
  @Test
  public void customBodyHandlerTest(Vertx vertx, VertxTestContext testContext) {
    RouterBuilder.create(vertx, "src/test/resources/specs/upload_test.yaml", testContext.succeeding(routerBuilder -> {
      routerBuilder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false));

      BodyHandler bodyHandler = BodyHandler.create("my-uploads");

      routerBuilder.rootHandler(bodyHandler);

      routerBuilder.operation("upload").handler(routingContext -> routingContext.response().setStatusCode(201).end());

      testContext.verify(() -> {
        assertThat(routerBuilder.createRouter().getRoutes().get(0))
          .extracting("state")
          .extracting("contextHandlers")
          .asList()
          .hasOnlyOneElementSatisfying(b -> assertThat(b).isSameAs(bodyHandler));
      });

      testContext.completeNow();

    }));
  }

  @Test
  public void testSharedRequestBody(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);

    loadBuilderAndStartServer(vertx, "src/test/resources/specs/shared_request_body.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);

        final Handler<RoutingContext> handler = routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          RequestParameter body = params.body();
          JsonObject jsonBody = body.getJsonObject();
          routingContext
            .response()
            .setStatusCode(200)
            .setStatusMessage("OK")
            .putHeader("Content-Type", "application/json")
            .end(jsonBody.toBuffer());
        };

        routerBuilder.operation("thisWayWorks").handler(handler);
        routerBuilder.operation("thisWayBroken").handler(handler);
      }).onComplete(h -> {
      JsonObject obj = new JsonObject().put("id", "aaa").put("name", "bla");
      testRequest(client, HttpMethod.POST, "/v1/working")
        .expect(statusCode(200))
        .expect(jsonBodyResponse(obj))
        .sendJson(obj, testContext, checkpoint);
      testRequest(client, HttpMethod.POST, "/v1/notworking")
        .expect(statusCode(200))
        .expect(jsonBodyResponse(obj))
        .sendJson(obj, testContext, checkpoint);
    });
  }

  @Test
  public void pathResolverShouldNotCreateRegex(Vertx vertx, VertxTestContext testContext) {
    RouterBuilder.create(vertx, "src/test/resources/specs/produces_consumes_test.yaml",
      testContext.succeeding(routerBuilder -> {
        routerBuilder.setOptions(new RouterBuilderOptions().setMountNotImplementedHandler(false));

        routerBuilder.operation("consumesTest").handler(routingContext ->
          routingContext
            .response()
            .setStatusCode(200)
            .setStatusMessage("OK")
        );

        testContext.verify(() ->
          assertThat(routerBuilder.createRouter().getRoutes())
            .extracting(Route::getPath)
            .anyMatch("/consumesTest"::equals)
        );

        testContext.completeNow();
      }));
  }

  @Test
  public void testJsonEmptyBody(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false).setMountNotImplementedHandler(false));

        routerBuilder.operation("jsonEmptyBody").handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          RequestParameter body = params.body();
          routingContext
            .response()
            .setStatusCode(200)
            .setStatusMessage("OK")
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("bodyEmpty", body == null).toBuffer());
        });

        testContext.completeNow();
      }).onComplete(h ->
      testRequest(client, HttpMethod.POST, "/jsonBody/empty")
        .expect(statusCode(200), jsonBodyResponse(new JsonObject().put("bodyEmpty", true)))
        .send(testContext)
    );
  }

  @Test
  public void commaSeparatedMultipartEncoding(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(3);
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/multipart.yaml", testContext, routerBuilder -> {
      routerBuilder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false));
      routerBuilder.operation("testMultipartMultiple").handler(routingContext -> {
        RequestParameters params = routingContext.get("parsedParameters");
        routingContext
          .response()
          .setStatusCode(200)
          .setStatusMessage(params.body().getJsonObject().getString("type"))
          .end();
      });
    }).onComplete(h -> {
      MultipartForm form1 = MultipartForm
        .create()
        .binaryFileUpload("file1", "random-file", "src/test/resources/random-file", "application/octet-stream")
        .attribute("type", "application/octet-stream");
      testRequest(client, HttpMethod.POST, "/testMultipartMultiple")
        .expect(statusCode(200), statusMessage("application/octet-stream"))
        .sendMultipartForm(form1, testContext, checkpoint);

      MultipartForm form2 =
        MultipartForm
          .create()
          .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "text/plain")
          .attribute("type", "text/plain");
      testRequest(client, HttpMethod.POST, "/testMultipartMultiple")
        .expect(statusCode(200), statusMessage("text/plain"))
        .sendMultipartForm(form2, testContext, checkpoint);

      MultipartForm form3 =
        MultipartForm
          .create()
          .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "application/json")
          .attribute("type", "application/json");
      testRequest(client, HttpMethod.POST, "/testMultipartMultiple")
        .expect(statusCode(400))
        .sendMultipartForm(form3, testContext, checkpoint);

    });
  }

  @Test
  public void wildcardMultipartEncoding(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(3);
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/multipart.yaml", testContext, routerBuilder -> {
      routerBuilder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false));
      routerBuilder.operation("testMultipartWildcard").handler(routingContext -> {
        RequestParameters params = routingContext.get("parsedParameters");
        routingContext
          .response()
          .setStatusCode(200)
          .setStatusMessage(params.body().getJsonObject().getString("type"))
          .end();
      });
    }).onComplete(h -> {
      MultipartForm form1 =
        MultipartForm
          .create()
          .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "text/plain")
          .attribute("type", "text/plain");
      testRequest(client, HttpMethod.POST, "/testMultipartWildcard")
        .expect(statusCode(200), statusMessage("text/plain"))
        .sendMultipartForm(form1, testContext, checkpoint);

      MultipartForm form2 =
        MultipartForm
          .create()
          .binaryFileUpload("file1", "random.csv", "src/test/resources/random.csv", "text/csv")
          .attribute("type", "text/csv");
      testRequest(client, HttpMethod.POST, "/testMultipartWildcard")
        .expect(statusCode(200), statusMessage("text/csv"))
        .sendMultipartForm(form2, testContext, checkpoint);

      MultipartForm form3 =
        MultipartForm
          .create()
          .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "application/json")
          .attribute("type", "application/json");
      testRequest(client, HttpMethod.POST, "/testMultipartWildcard")
        .expect(statusCode(400))
        .sendMultipartForm(form3, testContext, checkpoint);
    });
  }

  @Test
  public void multipleWildcardMultipartEncoding(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(6);
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/multipart.yaml", testContext, routerBuilder -> {
      routerBuilder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false));
      routerBuilder.operation("testMultipartMultipleWildcard").handler(routingContext -> {
        routingContext
          .response()
          .setStatusCode(200)
          .end();
      });
    }).onComplete(h -> {
      testRequest(client, HttpMethod.POST, "/testMultipartMultipleWildcard")
        .expect(statusCode(200))
        .sendMultipartForm(MultipartForm
            .create()
            .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "text/plain")
            .binaryFileUpload("file2", "random.txt", "src/test/resources/random.txt", "text/json"),
          testContext, checkpoint
        );

      testRequest(client, HttpMethod.POST, "/testMultipartMultipleWildcard")
        .expect(statusCode(200))
        .sendMultipartForm(MultipartForm
            .create()
            .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "application/json")
            .binaryFileUpload("file2", "random.txt", "src/test/resources/random.txt", "text/json"),
          testContext, checkpoint
        );

      testRequest(client, HttpMethod.POST, "/testMultipartMultipleWildcard")
        .expect(statusCode(400))
        .sendMultipartForm(
          MultipartForm
            .create()
            .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "text/json")
            .binaryFileUpload("file2", "random.txt", "src/test/resources/random.txt", "text/json"),
          testContext, checkpoint
        );

      testRequest(client, HttpMethod.POST, "/testMultipartMultipleWildcard")
        .expect(statusCode(200))
        .sendMultipartForm(
          MultipartForm
            .create()
            .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "text/plain")
            .binaryFileUpload("file2", "random.txt", "src/test/resources/random.txt", "text/json"),
          testContext, checkpoint
        );

      testRequest(client, HttpMethod.POST, "/testMultipartMultipleWildcard")
        .expect(statusCode(200))
        .sendMultipartForm(
          MultipartForm
            .create()
            .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "text/plain")
            .binaryFileUpload("file2", "random.txt", "src/test/resources/random.txt", "application/json"),
          testContext, checkpoint
        );

      testRequest(client, HttpMethod.POST, "/testMultipartMultipleWildcard")
        .expect(statusCode(400))
        .sendMultipartForm(
          MultipartForm
            .create()
            .binaryFileUpload("file1", "random.txt", "src/test/resources/random.txt", "text/plain")
            .binaryFileUpload("file2", "random.txt", "src/test/resources/random.txt", "bla/json"),
          testContext, checkpoint
        );

    });
  }

  @Test
  public void testQueryParamNotRequired(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("listPets")
        .handler(routingContext -> routingContext.response().setStatusMessage("ok").end());
    }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(200), statusMessage("ok"))
        .send(testContext)
    );
  }

  @Test
  public void testPathParameter(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("showPetById")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          routingContext.response().setStatusMessage(params.pathParameter("petId").toString()).end();
        });
    }).onComplete(h -> {
      testRequest(client, HttpMethod.GET, "/pets/3")
        .expect(statusCode(200), statusMessage("3"))
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/pets/three")
        .expect(statusCode(400))
        .expect(badParameterResponse(ParameterProcessorException.ParameterProcessorErrorType.PARSING_ERROR, "petId", ParameterLocation.PATH))
        .send(testContext, checkpoint);
    });
  }

  @Test
  public void testQueryParameterArrayExploded(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("arrayTestFormExploded")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          String serialized = params
            .queryParameter("parameter")
            .getJsonArray()
            .stream()
            .map(Object::toString)
            .collect(Collectors.joining(","));
          routingContext.response().setStatusMessage(serialized).end();
        });
    }).onComplete(h -> {
      QueryStringEncoder encoder = new QueryStringEncoder("/queryTests/arrayTests/formExploded");
      List<String> values = new ArrayList<>();
      values.add("4");
      values.add("2");
      values.add("26");
      for (String s : values) {
        encoder.addParam("parameter", s);
      }
      String serialized = String.join(",", values);

      testRequest(client, HttpMethod.GET, encoder.toString())
        .expect(statusCode(200), statusMessage(serialized))
        .send(testContext);
    });
  }

  @Test
  public void testQueryParameterArrayExplodedObject(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("arrayTestFormExplodedObject")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          String serialized = params
            .queryParameter("parameter")
            .getJsonArray()
            .stream()
            .map(JsonObject.class::cast)
            .map(JsonObject::encode)
            .collect(Collectors.joining(","));
          routingContext.response().setStatusMessage(serialized).end();
        });
    }).onComplete(h -> {
      QueryStringEncoder encoder = new QueryStringEncoder("/queryTests/arrayTests/formExplodedObject");
      List<String> values = new ArrayList<>();
      JsonObject fooBar1 = new JsonObject().put("foo", "bar1");
      JsonObject fooBar2 = new JsonObject().put("foo", "bar2");
      values.add(fooBar1.encode());
      values.add(fooBar2.encode());
      for (String s : values) {
        encoder.addParam("parameter", s);
      }
      String serialized = String.join(",", values);

      testRequest(client, HttpMethod.GET, encoder.toString())
        .expect(statusCode(200), statusMessage(serialized))
        .send(testContext);
    });
  }

  @Test
  public void testQueryParameterArrayDefaultStyle(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);
      routerBuilder
        .operation("arrayTest")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          String serialized = params
            .queryParameter("parameter")
            .getJsonArray()
            .stream()
            .map(Object::toString)
            .collect(Collectors.joining(","));
          routingContext.response().setStatusMessage(serialized).end();
        });
    }).onComplete(h -> {
      String serialized = String.join(",", "4", "2", "26");
      testRequest(client, HttpMethod.GET, "/queryTests/arrayTests/default?parameter=" + serialized)
        .expect(statusCode(200), statusMessage(serialized))
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/queryTests/arrayTests/default?parameter=" + String.join(",", "4", "1", "26"))
        .expect(statusCode(400))
        .expect(badParameterResponse(ParameterProcessorException.ParameterProcessorErrorType.VALIDATION_ERROR, "parameter", ParameterLocation.QUERY))
        .send(testContext, checkpoint);
    });
  }

  @Test
  public void testDefaultStringQueryParameter(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("testDefaultString")
        .handler(routingContext ->
          routingContext.response().setStatusMessage(
            ((RequestParameters) routingContext.get("parsedParameters")).queryParameter("parameter").getString()
          ).end()
        );
    }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/queryTests/defaultString")
        .expect(statusCode(200), statusMessage("aString"))
        .send(testContext)
    );
  }


  @Test
  public void testDefaultIntQueryParameter(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("testDefaultInt")
        .handler(routingContext ->
          routingContext.response().setStatusMessage(
            ((RequestParameters) routingContext.get("parsedParameters")).queryParameter("parameter").getInteger().toString()
          ).end()
        );
    }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/queryTests/defaultInt")
        .expect(statusCode(200), statusMessage("1"))
        .send(testContext)
    );
  }

  @Test
  public void testDefaultFloatQueryParameter(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("testDefaultFloat")
        .handler(routingContext ->
          routingContext.response().setStatusMessage(
            ((RequestParameters) routingContext.get("parsedParameters")).queryParameter("parameter").getFloat().toString()
          ).end()
        );
    }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/queryTests/defaultFloat")
        .expect(statusCode(200), statusMessage("1.0"))
        .send(testContext)
    );
  }

  @Test
  public void testDefaultDoubleQueryParameter(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("testDefaultDouble")
        .handler(routingContext ->
          routingContext.response().setStatusMessage(
            ((RequestParameters) routingContext.get("parsedParameters")).queryParameter("parameter").getDouble().toString()
          ).end()
        );
    }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/queryTests/defaultDouble")
        .expect(statusCode(200), statusMessage("1.0"))
        .send(testContext)
    );
  }

  @Test
  public void testAllowEmptyValueStringQueryParameter(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("testDefaultString")
        .handler(routingContext ->
          routingContext.response().setStatusMessage(
            "" + ((RequestParameters) routingContext.get("parsedParameters")).queryParameter("parameter").getString().length()
          ).end()
        );
    }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/queryTests/defaultString?parameter")
        .expect(statusCode(200), statusMessage("0"))
        .send(testContext)
    );
  }

  @Test
  public void testAllowEmptyValueBooleanQueryParameter(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(3);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("testDefaultBoolean")
        .handler(routingContext ->
          routingContext.response().setStatusMessage(
            "" + ((RequestParameters) routingContext.get("parsedParameters")).queryParameter("parameter").toString()
          ).end()
        );
    }).onComplete(h -> {
      testRequest(client, HttpMethod.GET, "/queryTests/defaultBoolean?parameter")
        .expect(statusCode(200), statusMessage("false"))
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/queryTests/defaultBoolean")
        .expect(statusCode(200), statusMessage("false"))
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/queryTests/defaultBoolean?parameter=true")
        .expect(statusCode(200), statusMessage("true"))
        .send(testContext, checkpoint);
    });
  }

  @Test
  public void testQueryParameterByteFormat(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("byteFormatTest")
        .handler(routingContext ->
          routingContext.response().setStatusMessage(
            "" + ((RequestParameters) routingContext.get("parsedParameters")).queryParameter("parameter").toString()
          ).end()
        );
    }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/queryTests/byteFormat?parameter=Zm9vYmFyCg==")
        .expect(statusCode(200), statusMessage("Zm9vYmFyCg=="))
        .send(testContext)
    );
  }

  @Test
  public void testFormArrayParameter(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);
      routerBuilder
        .operation("formArrayTest")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          String serialized = params
            .body()
            .getJsonObject()
            .getJsonArray("values")
            .stream()
            .map(Object::toString)
            .collect(Collectors.joining(","));
          routingContext.response().setStatusMessage(
            params.body().getJsonObject().getString("id") + serialized
          ).end();
        });
    }).onComplete(h -> {
      testRequest(client, HttpMethod.POST, "/formTests/arraytest")
        .expect(statusCode(200), statusMessage("a+b+c" + "10,8,4"))
        .sendURLEncodedForm(MultiMap.caseInsensitiveMultiMap().add("id", "a+b+c").add("values", (Iterable<String>) Arrays.asList("10", "8", "4")), testContext, checkpoint);

      testRequest(client, HttpMethod.POST, "/formTests/arraytest")
        .expect(statusCode(400))
        .expect(badBodyResponse(BodyProcessorException.BodyProcessorErrorType.PARSING_ERROR))
        .sendURLEncodedForm(MultiMap.caseInsensitiveMultiMap().add("id", "id").add("values", (Iterable<String>) Arrays.asList("10", "bla", "4")), testContext, checkpoint);
    });
  }

  @Test
  public void testJsonBody(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(4);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("jsonBodyTest")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          routingContext
            .response()
            .setStatusCode(200)
            .setStatusMessage("OK")
            .putHeader("Content-Type", "application/json")
            .end(params.body().getJsonObject().encode());
        });
    }).onComplete(h -> {
      JsonObject valid = new JsonObject().put("id", "anId").put("values", new JsonArray().add(5).add(10).add(2));

      testRequest(client, HttpMethod.POST, "/jsonBodyTest/sampleTest")
        .expect(statusCode(200), jsonBodyResponse(valid))
        .sendJson(valid, testContext, checkpoint);

      testRequest(client, HttpMethod.POST, "/jsonBodyTest/sampleTest")
        .with(requestHeader("content-type", "application/json; charset=utf-8"))
        .expect(statusCode(200), jsonBodyResponse(valid))
        .sendBuffer(valid.toBuffer(), testContext, checkpoint);

      testRequest(client, HttpMethod.POST, "/jsonBodyTest/sampleTest")
        .with(requestHeader("content-type", "application/superapplication+json"))
        .expect(statusCode(200), jsonBodyResponse(valid))
        .sendBuffer(valid.toBuffer(), testContext, checkpoint);

      JsonObject invalid = new JsonObject().put("id", "anId").put("values", new JsonArray().add(5).add("bla").add(2));

      testRequest(client, HttpMethod.POST, "/jsonBodyTest/sampleTest")
        .expect(statusCode(400))
        .expect(badBodyResponse(BodyProcessorException.BodyProcessorErrorType.VALIDATION_ERROR))
        .sendJson(invalid, testContext, checkpoint);

    });
  }

  @Test
  public void testRequiredJsonBody(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);
      routerBuilder
        .operation("createPets")
        .handler(routingContext ->
          routingContext
            .response()
            .setStatusCode(200)
            .end()
        );
    }).onComplete(h ->
      testRequest(client, HttpMethod.POST, "/pets")
        .expect(statusCode(400))
        .expect(failurePredicateResponse())
        .send(testContext)
    );
  }

  @Test
  public void testAllOfQueryParam(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(4);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);
      routerBuilder
        .operation("alloftest")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          routingContext
            .response()
            .setStatusMessage("" +
              params.queryParameter("parameter").getJsonObject().getInteger("a") +
              params.queryParameter("parameter").getJsonObject().getBoolean("b")
            ).end();
        });
    }).onComplete(h -> {

      testRequest(client, HttpMethod.GET, "/queryTests/allOfTest?parameter=a,5,b,true")
        .expect(statusCode(200), statusMessage("5true"))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.GET, "/queryTests/allOfTest?parameter=a,5,b,")
        .expect(statusCode(400))
        .expect(badParameterResponse(ParameterProcessorException.ParameterProcessorErrorType.VALIDATION_ERROR, "parameter", ParameterLocation.QUERY))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.GET, "/queryTests/allOfTest?parameter=a,5")
        .expect(statusCode(200), statusMessage("5false"))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.GET, "/queryTests/allOfTest?parameter=a,5,b,bla")
        .expect(statusCode(400))
        .expect(badParameterResponse(ParameterProcessorException.ParameterProcessorErrorType.PARSING_ERROR, "parameter", ParameterLocation.QUERY))
        .send(testContext, checkpoint);

    });
  }

  @Test
  public void testQueryParameterAnyOf(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(5);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("anyOfTest")
        .handler(routingContext ->
          routingContext
            .response()
            .setStatusMessage(((RequestParameters) routingContext.get("parsedParameters")).queryParameter("parameter").toString())
            .end()
        );
    }).onComplete(h -> {

      testRequest(client, HttpMethod.GET, "/queryTests/anyOfTest?parameter=true")
        .expect(statusCode(200), statusMessage("true"))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.GET, "/queryTests/anyOfTest?parameter=5")
        .expect(statusCode(200), statusMessage("5"))
        .send(testContext, checkpoint);


      testRequest(client, HttpMethod.GET, "/queryTests/anyOfTest?parameter=5,4")
        .expect(statusCode(200), statusMessage(new JsonArray().add(5).add(4).encode()))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.GET, "/queryTests/anyOfTest?parameter=a,5")
        .expect(statusCode(200), statusMessage(new JsonObject().put("a", 5).encode()))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.GET, "/queryTests/anyOfTest?parameter=bla")
        .expect(statusCode(400))
        .expect(badParameterResponse(ParameterProcessorException.ParameterProcessorErrorType.PARSING_ERROR, "parameter", ParameterLocation.QUERY))
        .send(testContext, checkpoint);

    });
  }

  @Timeout(2000)
  @Test
  public void testComplexMultipart(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);
      routerBuilder
        .operation("complexMultipartRequest")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          if (params.body() == null) {
            routingContext.response().setStatusCode(200).end();
          } else {
            routingContext
              .response()
              .putHeader("content-type", "application/json")
              .setStatusCode(200)
              .end(params.body().getJsonObject().toBuffer());
          }
        });
    }).onComplete(h -> {

      JsonObject pet = new JsonObject();
      pet.put("id", 14612);
      pet.put("name", "Willy");

      MultipartForm form = MultipartForm.create()
        .textFileUpload("param1", "random.txt", "src/test/resources/random.txt", "text/plain")
        .attribute("param2", pet.encode())
        .textFileUpload("param3", "random.csv", "src/test/resources/random.txt", "text/csv")
        .attribute("param4", "1.2")
        .attribute("param4", "5.2")
        .attribute("param4", "6.2")
        .attribute("param5", "2")
        .binaryFileUpload("param1NotRealBinary", "random-file", "src/test/resources/random-file", "text/plain")
        .binaryFileUpload("param1Binary", "random-file", "src/test/resources/random-file", "application/octet-stream");

      JsonObject expected = new JsonObject()
        .put("param2", pet)
        .put("param4", new JsonArray().add(1.2).add(5.2).add(6.2))
        .put("param5", 2);

      testRequest(client, HttpMethod.POST, "/multipart/complex")
        .expect(statusCode(200))
        .expect(jsonBodyResponse(expected))
        .sendMultipartForm(form, testContext, checkpoint);

      testRequest(client, HttpMethod.POST, "/multipart/complex")
        .expect(statusCode(200))
        .send(testContext, checkpoint);

    });
  }

  @Test
  public void testEmptyParametersNotNull(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("createPets")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          routingContext.response().setStatusCode(200).setStatusMessage( //Here it should not throw exception (issue
            // #850)
            "" + params.queryParametersNames().size() + params.pathParametersNames().size() +
              params.cookieParametersNames().size() + params.headerParametersNames().size()
          ).end();
        });
    }).onComplete(h -> {
      testRequest(client, HttpMethod.POST, "/pets")
        .expect(statusCode(200), statusMessage("0000"))
        .sendJson(new JsonObject().put("id", 1).put("name", "Willy"), testContext);
    });
  }

  @Test
  public void testQueryExpandedObjectTestOnlyAdditionalProperties(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(3);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);
      routerBuilder
        .operation("objectTestOnlyAdditionalProperties")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          testContext.verify(() -> {
            assertThat(params.queryParameter("wellKnownParam").getString()).isEqualTo("hello");

            JsonObject param = params.queryParameter("params").getJsonObject();
            assertThat(param.containsKey("wellKnownParam")).isFalse();
            assertThat(param.getInteger("param1")).isEqualTo(1);
            assertThat(param.getInteger("param2")).isEqualTo(2);
          });
          routingContext.response().setStatusCode(200).end();
        });
    }).onComplete(h -> {
      testRequest(client, HttpMethod.GET, "/queryTests/objectTests/onlyAdditionalProperties?param1=1&param2=2&wellKnownParam=hello")
        .expect(statusCode(200))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.GET, "/queryTests/objectTests/onlyAdditionalProperties?param1=1&param2=a&wellKnownParam=hello")
        .expect(statusCode(400))
        .expect(badParameterResponse(ParameterProcessorException.ParameterProcessorErrorType.PARSING_ERROR, "params", ParameterLocation.QUERY))
        .send(testContext, checkpoint);

      testRequest(client, HttpMethod.GET, "/queryTests/objectTests/onlyAdditionalProperties?param1=1&param2=2&wellKnownParam=a")
        .expect(statusCode(400))
        .expect(badParameterResponse(ParameterProcessorException.ParameterProcessorErrorType.VALIDATION_ERROR, "wellKnownParam", ParameterLocation.QUERY))
        .send(testContext, checkpoint);
    });
  }

  @Test
  public void testJsonBodyWithDate(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("jsonBodyWithDate")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          routingContext
            .response()
            .setStatusCode(200)
            .setStatusMessage("OK")
            .putHeader("Content-Type", "application/json")
            .end(params.body().getJsonObject().encode());
        });
    }).onComplete(h -> {
      JsonObject obj = new JsonObject();
      obj.put("date", "2018-02-18");
      obj.put("dateTime1", "2018-01-01T10:00:00.0000000000000000000000Z");
      obj.put("dateTime2", "2018-01-01T10:00:00+10:00");
      obj.put("dateTime3", "2018-01-01T10:00:00-10:00");

      testRequest(client, HttpMethod.POST, "/jsonBodyWithDate")
        .expect(statusCode(200))
        .expect(jsonBodyResponse(obj))
        .sendJson(obj, testContext);
    });
  }


  /**
   * Test: query_optional_form_explode_object
   */
  @Test
  public void testQueryOptionalFormExplodeObject(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("query_form_explode_object")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          routingContext.response()
            .setStatusCode(200)
            .setStatusMessage("OK")
            .putHeader("content-type", "application/json")
            .end(params.queryParameter("color").getJsonObject().encode());
        });
    }).onComplete(h -> {
      testRequest(client, HttpMethod.GET, "/query/form/explode/object?R=100&G=200&B=150&alpha=50")
        .expect(statusCode(200))
        .expect(jsonBodyResponse(new JsonObject("{\"R\":\"100\",\"G\":\"200\",\"B\":\"150\",\"alpha\":50}")))
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/query/form/explode/object?R=100&G=200&B=150&alpha=aaa")
        .expect(statusCode(400))
        .expect(badParameterResponse(ParameterProcessorException.ParameterProcessorErrorType.PARSING_ERROR, "color", ParameterLocation.QUERY))
        .send(testContext, checkpoint);
    });
  }

  /**
   * Test: binary_test
   */
  @Test
  public void testOctetStreamBody(Vertx vertx, VertxTestContext testContext) {
    Buffer body = Buffer.buffer("Hello World!");
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder
        .operation("binary_test")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          routingContext.response()
            .setStatusCode(200)
            .setStatusMessage("OK")
            .putHeader("content-type", "application/octet-stream")
            .end(params.body().getBuffer());
        });
    }).onComplete(h -> {
      testRequest(client, HttpMethod.POST, "/binaryTest")
        .expect(statusCode(200))
        .expect(bodyResponse(body, "application/octet-stream"))
        .with(requestHeader("content-type", "application/octet-stream"))
        .sendBuffer(body, testContext);
    });
  }

  @Test
  public void mountContractEndpoint(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(5);
    loadBuilderAndStartServer(vertx, "src/test/resources/specs/router_builder_test.yaml", testContext,
      routerBuilder -> {
        routerBuilder.setOptions(
          new RouterBuilderOptions()
            .setMountNotImplementedHandler(true)
            .setRequireSecurityHandlers(false)
            .setContractEndpoint(RouterBuilderOptions.STANDARD_CONTRACT_ENDPOINT)
        );
      }).onComplete(h -> {
      testRequest(client, HttpMethod.GET, "/openapi")
        .expect(
          statusCode(200),
          responseHeader(HttpHeaders.CONTENT_TYPE.toString(), "text/yaml"),
          stringBody(body -> assertThat(body).containsIgnoringCase("openapi"))
        )
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/openapi")
        .with(requestHeader("Accept", "application/json"))
        .expect(
          statusCode(200),
          responseHeader(HttpHeaders.CONTENT_TYPE.toString(), "application/json"),
          stringBody(body -> assertThat(body).containsIgnoringCase("openapi"))
        )
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/openapi")
        .with(queryParam("format", "yaml"))
        .expect(
          statusCode(200),
          responseHeader(HttpHeaders.CONTENT_TYPE.toString(), "text/yaml"),
          stringBody(body -> assertThat(body).containsIgnoringCase("openapi"))
        )
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/openapi")
        .with(queryParam("format", "json"))
        .expect(
          statusCode(200),
          responseHeader(HttpHeaders.CONTENT_TYPE.toString(), "application/json"),
          stringBody(body -> assertThat(body).containsIgnoringCase("openapi"))
        )
        .send(testContext, checkpoint);
      testRequest(client, HttpMethod.GET, "/openapi")
        .with(queryParam("format", "JSON"))
        .expect(
          statusCode(200),
          responseHeader(HttpHeaders.CONTENT_TYPE.toString(), "application/json"),
          stringBody(body -> assertThat(body).containsIgnoringCase("openapi"))
        )
        .send(testContext, checkpoint);
    });
  }

  @Test
  public void testHeaderCaseInsensitive(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {
      routerBuilder.setOptions(HANDLERS_TESTS_OPTIONS);
      routerBuilder
        .operation("headerCaseInsensitive")
        .handler(routingContext -> {
          RequestParameters params = routingContext.get("parsedParameters");
          testContext.verify(() -> {
            assertThat(params.headerParameter("CASEINSENSITIVE").getString())
              .isEqualTo("hello");
          });
          routingContext.response().setStatusCode(200).end();
        });
    }).onComplete(h -> {
      testRequest(client, HttpMethod.GET, "/headerCaseInsensitive")
        .with(requestHeader("cASEiNSENSITIVE", "hello"))
        .expect(statusCode(200))
        .send(testContext);
    });
  }

  @Timeout(10000)
  @Test
  public void testIssue1876(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    loadBuilderAndStartServer(vertx, "specs/repro_1876.yaml", testContext, routerBuilder -> {
      routerBuilder.setOptions(new RouterBuilderOptions().setRequireSecurityHandlers(false).setMountNotImplementedHandler(true));
      routerBuilder
        .operation("createAccount")
        .handler(routingContext ->
          routingContext
            .response()
            .setStatusCode(200)
            .end()
        );
      routerBuilder
        .operation("createAccountMember")
        .handler(routingContext ->
          routingContext
            .response()
            .setStatusCode(200)
            .end()
        );
    }).onComplete(h -> {
      testRequest(client, HttpMethod.POST, "/accounts")
        .expect(statusCode(200), emptyResponse())
        .sendJson(new JsonObject()
            .put("data", new JsonObject()
              .put("id", 123)
              .put("type", "account-registration")
              .put("attributes", new JsonObject()
                .put("ownerEmail", "test@gmail.com")
              )
            ),
          testContext,
          checkpoint
        );

      testRequest(client, HttpMethod.POST, "/members")
        .expect(statusCode(200), emptyResponse())
        .sendJson(new JsonObject()
            .put("data", new JsonObject()
              .put("type", "account-member-registration")
              .put("attributes", new JsonObject()
                .put("email", "test@gmail.com")
              )
            ),
          testContext,
          checkpoint
        );
    });
  }

  @Test
  public void testProperOrderOfHandlers(Vertx vertx, VertxTestContext testContext) {
    loadBuilderAndStartServer(vertx, VALIDATION_SPEC, testContext, routerBuilder -> {

      routerBuilder.rootHandler(LoggerHandler.create(true, LoggerHandler.DEFAULT_FORMAT));
      routerBuilder.rootHandler(TimeoutHandler.create(180000));
      routerBuilder.rootHandler(CorsHandler.create("*"));
      routerBuilder.rootHandler(BodyHandler.create().setBodyLimit(40000000).setDeleteUploadedFilesOnEnd(true).setHandleFileUploads(true));

      routerBuilder
        .operation("listPets")
        .handler(routingContext -> routingContext.response().setStatusMessage("ok").end());
    }).onComplete(h ->
      testRequest(client, HttpMethod.GET, "/pets")
        .expect(statusCode(200), statusMessage("ok"))
        .send(testContext)
    );
  }

  @Test
  public void testIncorrectOrderOfHandlers(Vertx vertx, VertxTestContext testContext) {

    RouterBuilder.create(vertx, VALIDATION_SPEC, testContext.succeeding(routerBuilder -> {
      routerBuilder.rootHandler(BodyHandler.create().setBodyLimit(40000000).setDeleteUploadedFilesOnEnd(true).setHandleFileUploads(true));
      routerBuilder.rootHandler(LoggerHandler.create(true, LoggerHandler.DEFAULT_FORMAT));
      routerBuilder.rootHandler(TimeoutHandler.create(180000));
      routerBuilder.rootHandler(CorsHandler.create("*"));

      routerBuilder
        .operation("listPets")
        .handler(routingContext -> routingContext.response().setStatusMessage("ok").end());

      try {
        routerBuilder.createRouter();
        testContext.failNow("Should not reach here");
      } catch (IllegalStateException ise) {
        testContext.completeNow();
      }
    }));
  }
}
