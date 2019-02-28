package iudx.catalogue.database;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.AbstractVerticle;
import io.vertx.ext.mongo.FindOptions;
import io.vertx.ext.mongo.MongoClient;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

public class MongoDB extends AbstractVerticle implements DatabaseInterface {

  private MongoClient mongo;

  private String ITEM_COLLECTION, SCHEMA_COLLECTION;
  /**
   * Constructor for MongoDB
   *
   * @param item_database Name of the Item Collection
   * @param schema_database Name of the Schema Collection
   */
  public MongoDB(String item_database, String schema_database) {

    ITEM_COLLECTION = item_database;
    SCHEMA_COLLECTION = schema_database;
  }

  public void init_db(Vertx vertx, JsonObject mongoconfig) {

    mongo = MongoClient.createShared(vertx, mongoconfig);
  }
  /**
   * Searches the Mongo DB
   *
   * @param collection Name of the collection
   * @param query Query to the MongoDB
   * @param options Options specify the fields that will (not) be displayed
   * @param message The message to which the result will be replied to
   */
  private void mongo_find(
      String collection, JsonObject query, FindOptions options, Message<Object> message) {

    JsonObject fields = options.getFields();
    fields.put("_id", 0);

    options.setFields(fields);

    mongo.findWithOptions(
        collection,
        query,
        options,
        res -> {
          if (res.succeeded()) {
            // Send back the response
            JsonArray rep = new JsonArray();
            for (JsonObject j : res.result()) {
              if (j.containsKey("_tags")) {
                j.remove("_tags");
              }
              rep.add(j);
            }
            message.reply(rep);
          } else {
            message.fail(0, "failure");
          }
        });
  }

  @Override
  public void search_attribute(Message<Object> message) {

    JsonObject request_body = (JsonObject) message.body();
    JsonObject query = new JsonObject();
    JsonObject fields = new JsonObject();

    // Populate query
    Iterator<Map.Entry<String, Object>> it = request_body.iterator();
    while (it.hasNext()) {
      String key = it.next().getKey();
      JsonArray values = request_body.getJsonArray(key);
      if (!key.equalsIgnoreCase("attributeFilter")) {
        if (key.equalsIgnoreCase("tags")) {
          if (values.size() == 1) {
            query.put("_tags", values.getString(0).toLowerCase());
          } else {
            JsonArray tag_values = new JsonArray();
            for (int i = 0; i < values.size(); i++) {
              tag_values.add(values.getString(i).toLowerCase());
            }
            query.put("_tags", new JsonObject().put("$in", tag_values));
          }
        } else {
          for (int i = 0; i < values.size(); i++) {
            query.put(key, values.getString(i));
          }
        }
      }
    }

    // Populate fields
    if (request_body.containsKey("attributeFilter")) {
      JsonArray filter = request_body.getJsonArray("attributeFilter");
      for (int i = 0; i < filter.size(); i++) {
        fields.put(filter.getString(i), 1);
      }
    }

    // Call mongo find
    FindOptions options = new FindOptions().setFields(fields);
    mongo_find(ITEM_COLLECTION, query, options, message);
  }

  @Override
  public void read_item(Message<Object> message) {

    JsonObject query = new JsonObject();
    JsonObject request_body = (JsonObject) message.body();

    // Populate query
    query.put("id", request_body.getString("id"));

    // Call mongo find
    mongo_find(ITEM_COLLECTION, query, new FindOptions(), message);
  }
  /**
   * Replaces the '$' in the fields of schema with '&'
   *
   * @param schema The schema whose fields have to be changed
   * @return The schema whose fields have '&' for '$'
   */
  private JsonObject encode_schema(JsonObject schema) {

    String[] temp = StringUtils.split(schema.encode(), "$");
    String encodedSchema = StringUtils.join(temp, "&");
    return new JsonObject(encodedSchema);
  }
  /**
   * Replaces the '&' in the fields of encoded schema with '$'
   *
   * @param encodedSchema The encoded schema whose state has to be reverted
   * @return The original schema, obtained by replacing the '&' in the fields of encoded schema by
   *     '$'
   */
  private JsonObject decode_schema(JsonObject encodedSchema) {

    String[] temp = StringUtils.split(encodedSchema.encode(), "&");
    String schema = StringUtils.join(temp, "$");
    return new JsonObject(schema);
  }

  @Override
  public void read_schema(Message<Object> message) {

    JsonObject m = (JsonObject) message.body();
    JsonObject query = new JsonObject();

    query.put("id", m.getString("id"));

    mongo.findOne(
        SCHEMA_COLLECTION,
        query,
        new JsonObject(),
        res -> {
          if (res.succeeded()) {
            message.reply(decode_schema(res.result()));
          } else {
            message.fail(0, "failure");
          }
        });
  }
  /**
   * Adds the fields id, Version, Status, Created, Last modified on to the given JsonObject
   *
   * @param doc The document that is being inserted into the database
   * @param version The version of the document
   * @return The JsonObject with additional fields
   */
  private JsonObject addNewAttributes(JsonObject doc, String version) {

    JsonObject updated = doc.copy();
    updated.put("Created", new java.util.Date().toString());
    updated.put("Last modified on", new java.util.Date().toString());
    updated.put("Status", "Live");
    updated.put("Version", version);
    updated.put("id", UUID.randomUUID().toString());
    updated.put("Provider", "iudx-provider");

    
    if (updated.containsKey("tags")) {
      JsonArray tagsInLowerCase = new JsonArray();
      JsonArray tags = updated.getJsonArray("tags");

      for (Object i : tags) {
        tagsInLowerCase.add(((String) i).toLowerCase());
      }
      updated.put("_tags", tagsInLowerCase);
    }

    return updated;
  }

  @Override
  public void write_item(Message<Object> message) {

    JsonObject request_body = (JsonObject) message.body();
    JsonObject updated_item = addNewAttributes(request_body, "1.0");

    mongo.insert(
        ITEM_COLLECTION,
        updated_item,
        res -> {
          if (res.succeeded()) {
            message.reply(updated_item.getString("id"));
          } else {
            message.fail(0, "failure");
          }
        });
  }

  @Override
  public void write_schema(Message<Object> message) {

    JsonObject request_body = (JsonObject) message.body();

    mongo.insert(
        SCHEMA_COLLECTION,
        encode_schema(request_body),
        res -> {
          if (res.succeeded()) {
            message.reply("success");
          } else {
            message.fail(0, "failure");
          }
        });
  }

  @Override
  public void update_item(Message<Object> message) {
    // TODO Auto-generated method stub

  }

  @Override
  public void update_schema(Message<Object> message) {
    // TODO Auto-generated method stub

  }

  @Override
  public void delete_item(Message<Object> message) {
    // TODO Auto-generated method stub
    JsonObject query = new JsonObject();
    JsonObject request_body = (JsonObject) message.body();

    // Populate query
    query.put("id", request_body.getString("id"));

    mongo.removeDocument(
        ITEM_COLLECTION,
        query,
        res -> {
          if (res.succeeded()) {
            message.reply("Success");
          } else {
            message.fail(0, "Failure");
          }
        });
  }

  @Override
  public void delete_schema(Message<Object> message) {
    // TODO Auto-generated method stub
    JsonObject query = new JsonObject();
    JsonObject request_body = (JsonObject) message.body();

    // Populate query
    query.put("id", request_body.getString("id"));

    mongo.removeDocument(
        SCHEMA_COLLECTION,
        query,
        res -> {
          if (res.succeeded()) {
            message.reply("Success");
          } else {
            message.fail(0, "Failure");
          }
        });
  }
}
