package iudx.catalogue.apiserver;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.Principal;
import java.util.logging.Logger;

import javax.net.ssl.SSLPeerUnverifiedException;

import org.apache.commons.codec.digest.DigestUtils;
import org.json.JSONObject;
import org.json.JSONTokener;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.groovy.ext.web.handler.StaticHandler_GroovyExtension;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Properties;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_1;

public class APIServerVerticle extends AbstractVerticle {

  private static final Logger logger = Logger.getLogger(APIServerVerticle.class.getName());

  static final int HTTP_STATUS_OK = 200;
  static final int HTTP_STATUS_CREATED = 201;
  static final int HTTP_STATUS_DELETED = 204;
  static final int HTTP_STATUS_BAD_REQUEST = 400;
  static final int HTTP_STATUS_NOT_FOUND = 404;
  static final int HTTP_STATUS_INTERNAL_SERVER_ERROR = 500;
  static final int HTTP_STATUS_UNAUTHORIZED = 401;
  private ArrayList<String> itemTypes;

  @Override
  public void start(Future<Void> startFuture) {

    populateItemTypes();

    Router router = defineApiRouting();

    setSystemProps();

    HttpServer server = createServer();

    int port = config().getInteger("http.port", 8443);

    server.requestHandler(router::accept).listen(port);

    logger.info("API Server Verticle started!");

    startFuture.complete();
  }

  private void populateItemTypes() {
    itemTypes = new ArrayList<String>();
    itemTypes.add("resource-item");
    itemTypes.add("data-model");
    itemTypes.add("access-object");
    itemTypes.add("resource-server");
    itemTypes.add("provider");
    itemTypes.add("base-schema");
    itemTypes.add("catalogue-item");
  }

  private HttpServer createServer() {
    ClientAuth clientAuth = ClientAuth.REQUEST;
    String keystore = config().getString("keystore");
    String keystorePassword = config().getString("keystorePassword");

    HttpServer server =
        vertx.createHttpServer(
            new HttpServerOptions()
                .setSsl(true)
                .setClientAuth(clientAuth)
                .setKeyStoreOptions(
                    new JksOptions().setPath(keystore).setPassword(keystorePassword)));
    return server;
  }

  private Router defineApiRouting() {
    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());

    router
        .route("/")
        .handler(
            routingContext -> {
              HttpServerResponse response = routingContext.response();
              response.sendFile("ui/landing/index.html");
            });
    router
        .route("/search")
        .handler(
            routingContext -> {
              HttpServerResponse response = routingContext.response();
              response.sendFile("ui/search/index.html");
            });

    // NEW APIs
    router.get("/list/catalogue/:itemtype").handler(this::list);
    router.get("/search/catalogue/attribute").handler(this::searchAttribute);
    router.get("/count/catalogue/attribute").handler(this::count);
    router.post("/create/catalogue/:itemtype").handler(this::create);
    router.put("/update/catalogue/:itemtype/:id").handler(this::update);
    router.delete("/remove/catalogue/:itemtype/:id").handler(this::delete);

    router.post("/create/catalogue/resource-item/bulk/:bulkId").handler(this::bulkCreate);
    router.patch("/update/catalogue/resource-item/bulk/:bulkId").handler(this::bulkUpdate);
    router.delete("/remove/catalogue/resource-item/bulk/:bulkId").handler(this::bulkDelete);

    router.route("/assets/*").handler(StaticHandler.create("ui/assets"));
    return router;
  }

  private void setSystemProps() {
    String keystore = config().getString("keystore");
    String keystorePassword = config().getString("keystorePassword");

    String truststore = config().getString("truststore");
    String truststorePassword = config().getString("truststorePassword");

    Properties systemProps = System.getProperties();

    systemProps.put("javax.net.ssl.keyStore", keystore);
    systemProps.put("javax.net.ssl.keyStorePassword", keystorePassword);

    systemProps.put("javax.net.ssl.trustStore", truststore);
    systemProps.put("javax.net.ssl.trustStorePassword", truststorePassword);

    System.setProperties(systemProps);
    logger.info("IUDX TLS Property Defined !");
  }

  /**
   * Checks if the user has necessary permission to write or delete from the database
   *
   * @param event The server request
   * @param path The URL to which the request is sent
   * @param file_path The path of the file which contains the list of users and their permissions
   * @return
   */
  private boolean authenticateRequest(RoutingContext routingContext, String file_path) {

    HttpServerRequest request = routingContext.request();
    boolean allowed = false;
    String authorization = request.getHeader("authorization");

    if (authorization != null) {
      final String userId;
      final String password;
      final String scheme;

      try {
        String[] parts = authorization.split(" ");
        scheme = parts[0];
        String[] credentials = new String(Base64.getDecoder().decode(parts[1])).split(":");
        userId = credentials[0];
        // when the header is: "user:"
        password = credentials.length > 1 ? credentials[1] : null;

        if (!"Basic".equals(scheme)) {

          handle401(routingContext, "Use Basic HTTP authorization");
        } else {
          if (userId != null && password != null) {
            try (InputStream inputStream = new FileInputStream(file_path)) {
              JSONObject users = new JSONObject(new JSONTokener(inputStream));
              JSONObject user;
              if (users.has(userId)) {
                user = users.getJSONObject(userId);
                if (password.equals(user.getString("password"))) {
                  allowed = true;
                } else {
                  handle400(routingContext, "Your password is invalid");
                }

                if (allowed && !user.getBoolean("write_permission")) {
                  allowed = false;
                  handle401(routingContext, "You do not have write access to the server");
                }
              } else {
                handle400(routingContext, "User " + userId + "is not registered");
              }

            } catch (Exception e) {
              handle500(routingContext);
            }

          } else {
            handle400(routingContext, "Add 'authenticaton' in the header of your request");
          }
        }
      } catch (Exception e) {
        handle401(routingContext, "Use Basic HTTP authentication");
      }

    } else {
      handle401(routingContext, "Use Basic HTTP authorization");
    }

    logger.info("Authentication ended with flag : " + allowed);
    return allowed;
  }

  private boolean decodeCertificate(RoutingContext routingContext) {

    boolean status = false;

    try {

      Principal _PeerPrincipal =
          routingContext.request().connection().sslSession().getPeerPrincipal();
      String PeerPrincipal = _PeerPrincipal.toString();
      String[] PeerPrincipalArray = PeerPrincipal.split(",");
      String PeerPrincipal_OID = PeerPrincipalArray[0];
      String PeerPrincipal_T = PeerPrincipalArray[1];
      String PeerPrincipal_SURNAME = PeerPrincipalArray[2];
      String PeerPrincipal_GIVENNAME = PeerPrincipalArray[3];
      String PeerPrincipal_ST = PeerPrincipalArray[4];
      String PeerPrincipal_C = PeerPrincipalArray[5];
      String PeerPrincipal_OU = PeerPrincipalArray[6];
      String PeerPrincipal_O = PeerPrincipalArray[7];
      String PeerPrincipal_EMAILADDRESS = PeerPrincipalArray[8];

      logger.info("getPeerPrincipal is " + PeerPrincipal);
      logger.info("PeerPrincipal_OID is " + PeerPrincipal_OID);
      logger.info("PeerPrincipal_T is " + PeerPrincipal_T);
      logger.info("PeerPrincipal_SURNAME is " + PeerPrincipal_SURNAME);
      logger.info("PeerPrincipal_GIVENNAME is " + PeerPrincipal_GIVENNAME);
      logger.info("PeerPrincipal_ST is " + PeerPrincipal_ST);
      logger.info("PeerPrincipal_C is " + PeerPrincipal_C);
      logger.info("PeerPrincipal_OU is " + PeerPrincipal_OU);
      logger.info("PeerPrincipal_O is " + PeerPrincipal_O);
      logger.info("PeerPrincipal_EMAILADDRESS is " + PeerPrincipal_EMAILADDRESS);

      Principal _LocalPrincipal =
          routingContext.request().connection().sslSession().getLocalPrincipal();
      String LocalPrincipal = _LocalPrincipal.toString();
      String[] LocalPrincipalArray = LocalPrincipal.split(",");
      String LocalPrincipal_CN = LocalPrincipalArray[0];
      String LocalPrincipal_OU = LocalPrincipalArray[1];
      String LocalPrincipal_O = LocalPrincipalArray[2];
      String LocalPrincipal_L = LocalPrincipalArray[3];
      String LocalPrincipal_ST = PeerPrincipalArray[4];
      String LocalPrincipal_C = PeerPrincipalArray[5];

      logger.info("getLocalPrincipal is " + LocalPrincipal);
      logger.info("LocalPrincipal_CN is " + LocalPrincipal_CN);
      logger.info("LocalPrincipal_OU is " + LocalPrincipal_OU);
      logger.info("LocalPrincipal_O is " + LocalPrincipal_O);
      logger.info("LocalPrincipal_L is " + LocalPrincipal_L);
      logger.info("LocalPrincipal_ST is " + LocalPrincipal_ST);
      logger.info("LocalPrincipal_C is " + LocalPrincipal_C);

      String[] email = PeerPrincipal_EMAILADDRESS.split("=");
      String[] emailID = email[1].split("@");
      String userName = emailID[0];
      String domain = emailID[1];
      logger.info("PeerPrincipal_EMAILADDRESS is " + emailID);
      logger.info("userName is " + userName);
      logger.info("domain is " + domain);

      String userName_SHA_1 = new DigestUtils(SHA_1).digestAsHex(userName);
      logger.info("userName in SHA-1 is " + userName_SHA_1);
      String emailID_SHA_1 = userName_SHA_1 + "@" + domain;
      logger.info("emailID in SHA-1 is " + emailID_SHA_1);

      if (PeerPrincipal_OID.contains("class:3")
          || PeerPrincipal_OID.contains("class:4")
          || PeerPrincipal_OID.contains("class:5")) {
        status = true;
        logger.info("Valid Certificate");
      } else {
        status = false;
        logger.info("Invalid Certificate");
      }

    } catch (SSLPeerUnverifiedException e) {
      status = false;
    }

    return status;
  }

  private void list(RoutingContext routingContext) {
    String currentType = routingContext.request().getParam("itemtype");

    if (currentType.equals("item-types")) {
      JsonArray allTypes = new JsonArray(itemTypes);
      JsonObject reply = new JsonObject().put("item-types", allTypes);
      handle200(routingContext, reply);
    } else if (currentType.equals("tags")) {
      JsonObject request_body = new JsonObject();
      databaseHandler("get-tags", routingContext, request_body);
    } else if (itemTypes.contains(currentType)) {
      JsonObject request_body = new JsonObject();
      request_body.put("item-type", currentType);
      databaseHandler("list", routingContext, request_body);
    } else {
      handle400(routingContext, currentType + " does not exist in the catalogue. ");
    }
  }

  /**
   * Sends a request to ValidatorVerticle to validate the item and DatabaseVerticle to insert it in
   * the database. Displays the id of the inserted item.
   *
   * @param event The server request which contains the item to be inserted in the database and the
   *     skip_validation header.
   */
  private void create(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();
    String skip_validation = "false";
    if (request.headers().contains("skip_validation")) {
      skip_validation = request.getHeader("skip_validation").toLowerCase();
    }

    if (decodeCertificate(routingContext)) {
      if (authenticateRequest(routingContext, "user.list")) {
        try {
          JsonObject request_body = routingContext.getBodyAsJson();
          request_body.put("id", "");
          DeliveryOptions validator_action = new DeliveryOptions();
          validator_action.addHeader("action", "validate-item");

          if (skip_validation != null) {
            if (!("true".equals(skip_validation)) && !("false".equals(skip_validation))) {
              handle400(routingContext, "Invalid value: skip_validation is not a boolean");
              return;
            } else {
              validator_action.addHeader("skip_validation", skip_validation);
            }
          }

          vertx
              .eventBus()
              .send(
                  "validator",
                  request_body,
                  validator_action,
                  validator_reply -> {
                    if (validator_reply.succeeded()) {
                      String itemType = request.getParam("itemtype");
                      request_body.put("item-type", itemType);
                      if (itemTypes.contains(itemType)) {
                        handle400(routingContext, "No such item-type exists");
                      } else {
                        databaseHandler("create", routingContext, request_body);
                      }
                    } else {
                      handle500(routingContext);
                    }
                  });

        } catch (Exception e) {
          handle400(routingContext, "Invalid item: Not a Json Object");
        }
      } else {
        handle401(routingContext, "Unauthorised");
      }
    } else {
      handle400(routingContext, "Certificate 'authenticaton' error");
    }
  }

  private void bulkCreate(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();

    if (decodeCertificate(routingContext)) {
      if (authenticateRequest(routingContext, "user.list")) {
        try {
          String bulkId = request.getParam("bulkId");
          JsonArray request_body = routingContext.getBodyAsJsonArray();
          JsonObject request_json_object = new JsonObject();
          request_json_object.put("items", request_body);
          request_json_object.put("bulk-id", bulkId);

          databaseHandler("bulkcreate", routingContext, request_json_object);

        } catch (Exception e) {
          handle400(routingContext, "Invalid item: Not a Json Object");
        }
      } else {
        handle401(routingContext, "Unauthorised");
      }
    } else {
      handle400(routingContext, "Certificate 'authenticaton' error");
    }
  }

  private JsonObject prepareQuery(String query) {
    JsonObject request_body = new JsonObject();
    if (!query.equals("") && query != null) {
      String[] queryParams = query.split("\\&");
      int queryLen = queryParams.length;

      for (int i = 0; i < queryLen; i++) {
        String key = queryParams[i].split("\\=")[0];
        String val = queryParams[i].split("\\=")[1];
        request_body.put(key, val);
      }
    }
    return request_body;
  }

  /**
   * Searches the database based on the given query and displays only those fields present in
   * attributeFilter.
   *
   * @param event The server request which contains the query and attributeFilter
   */
  private void searchAttribute(RoutingContext routingContext) {

    HttpServerRequest request = routingContext.request();

    String query;
    try {
      query = URLDecoder.decode(request.query().toString(), "UTF-8");
    } catch (UnsupportedEncodingException e) {
      handle400(routingContext, "Bad Query");
      return;
    }
    logger.info(query);

    JsonObject request_body = prepareQuery(query);

    databaseHandler("search-attribute", routingContext, request_body);
  }

  private void count(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();

    String query;
    try {
      query = URLDecoder.decode(request.query().toString(), "UTF-8");
    } catch (UnsupportedEncodingException e) {
      handle400(routingContext, "Bad Query");
      return;
    }
    logger.info(query);

    JsonObject request_body = prepareQuery(query);

    databaseHandler("count", routingContext, request_body);
  }

  /**
   * Deletes the item from the database
   *
   * @param event The server request which contains the id of the item.
   */
  private void delete(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();

    if (decodeCertificate(routingContext)) {
      if (authenticateRequest(routingContext, "user.list")) {
        JsonObject request_body = new JsonObject();

        String id = request.getParam("id");
        String itemType = request.getParam("itemtype");
        request_body.put("id", id);
        request_body.put("item-type", itemType);

        if (!itemTypes.contains(itemType)) {
          handle400(routingContext, "No such item-type exists!");
        } else {
          databaseHandler("delete", routingContext, request_body);
        }
      } else {
        handle401(routingContext, "Unauthorised");
      }
    } else {
      handle400(routingContext, "Certificate 'authenticaton' error");
    }
  }

  private void bulkDelete(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();

    if (decodeCertificate(routingContext)) {
      if (authenticateRequest(routingContext, "user.list")) {
        JsonObject request_body = new JsonObject();

        String bulkId = request.getParam("bulkId");
        request_body.put("bulk-id", bulkId);

        databaseHandler("bulkdelete", routingContext, request_body);
      } else {
        handle401(routingContext, "Unauthorised");
      }
    } else {
      handle400(routingContext, "Certificate 'authenticaton' error");
    }
  }
  /**
   * Updates the item from the database
   *
   * @param routingContext Contains the updated item
   */
  private void update(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();
    if (decodeCertificate(routingContext)) {
      if (authenticateRequest(routingContext, "user.list")) {
        try {
          JsonObject request_body = routingContext.getBodyAsJson();
          String id = request.getParam("id");
          if (id.equals(request_body.getString("id"))) {
            String itemType = request.getParam("itemtype");
            request_body.put("item-type", itemType);
            databaseHandler("update", routingContext, request_body);
          } else {
            handle400(routingContext, "Ids provided in the URI and object does not match");
          }
        } catch (Exception e) {
          handle400(routingContext, "Invalid item: Not a Json Object");
        }
      } else {
        handle401(routingContext, "Unauthorised");
      }
    } else {
      handle400(routingContext, "Certificate 'authenticaton' error");
    }
  }

  private void bulkUpdate(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();
    if (decodeCertificate(routingContext)) {
      if (authenticateRequest(routingContext, "user.list")) {
        try {
          JsonObject request_body = routingContext.getBodyAsJson();
          String bulkId = request.getParam("bulkId");
          request_body.put("bulk-id", bulkId);
          databaseHandler("bulkupdate", routingContext, request_body);

        } catch (Exception e) {
          handle400(routingContext, "Invalid item: Not a Json Object");
        }
      } else {
        handle401(routingContext, "Unauthorised");
      }
    } else {
      handle400(routingContext, "Certificate 'authenticaton' error");
    }
  }

  private void databaseHandler(
      String action, RoutingContext routingContext, JsonObject request_body) {

    DeliveryOptions database_action = new DeliveryOptions();
    database_action.addHeader("action", action);

    vertx
        .eventBus()
        .send(
            "database",
            request_body,
            database_action,
            database_reply -> {
              if (database_reply.succeeded()) {
                switch (action) {
                  case "list":
                  case "get-tags":
                  case "search-attribute":
                    handle200(routingContext, (JsonArray) database_reply.result().body());
                    break;
                  case "count":
                    handle200(routingContext, (JsonObject) database_reply.result().body());
                    break;
                  case "delete":
                    handle204(routingContext);
                    break;
                  case "create":
                    String id = database_reply.result().body().toString();
                    handle201(routingContext, id);
                    break;
                  case "update":
                    String status = database_reply.result().body().toString();
                    JsonObject s = new JsonObject().put("status", status);
                    handle200(routingContext, s);
                    break;
                  case "bulkcreate":
                    JsonObject reply = (JsonObject) database_reply.result().body();
                    handle200(routingContext, reply);
                    break;
                  case "bulkdelete":
                    handle204(routingContext);
                    break;
                  case "bulkupdate":
                    JsonObject rep = (JsonObject) database_reply.result().body();
                    handle200(routingContext, rep);
                    break;
                }
              } else {
                if (database_reply.cause().getMessage().equalsIgnoreCase("Failure")) {
                  handle500(routingContext);
                } else {
                  handle400(routingContext, database_reply.cause().getMessage());
                }
              }
            });
  }

  private String getStatusInJson(String status) {
    return (new JsonObject().put("Status", status)).encodePrettily();
  }

  private void handle400(RoutingContext routingContext, String status) {
    HttpServerResponse response = routingContext.response();
    String jsonStatus = getStatusInJson(status);
    response
        .setStatusCode(HTTP_STATUS_BAD_REQUEST)
        .putHeader("content-type", "application/json; charset=utf-8")
        .end(jsonStatus);
  }

  private void handle401(RoutingContext routingContext, String status) {
    HttpServerResponse response = routingContext.response();
    String jsonStatus = getStatusInJson(status);
    response
        .setStatusCode(HTTP_STATUS_UNAUTHORIZED)
        .putHeader("content-type", "application/json; charset=utf-8")
        .end(jsonStatus);
  }

  private void handle500(RoutingContext routingContext) {
    HttpServerResponse response = routingContext.response();

    response.setStatusCode(HTTP_STATUS_INTERNAL_SERVER_ERROR).end();
  }

  private void handle200(RoutingContext routingContext, JsonArray reply) {
    HttpServerResponse response = routingContext.response();

    response.setStatusCode(HTTP_STATUS_OK).end(reply.encodePrettily());
  }

  private void handle200(RoutingContext routingContext, JsonObject reply) {
    HttpServerResponse response = routingContext.response();

    response.setStatusCode(HTTP_STATUS_OK).end(reply.encodePrettily());
  }

  private void handle204(RoutingContext routingContext) {
    HttpServerResponse response = routingContext.response();

    response.setStatusCode(HTTP_STATUS_DELETED).end();
  }

  private void handle201(RoutingContext routingContext, String id) {
    HttpServerResponse response = routingContext.response();

    String JsonId = (new JsonObject().put("id", id)).encodePrettily();

    response.setStatusCode(HTTP_STATUS_CREATED).end(JsonId);
  }
}
