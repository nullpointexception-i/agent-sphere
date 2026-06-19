package com.buukle.agent.util.jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;

public final class JsonSchemaGenerator {

    private static final SchemaVersion SCHEMA_VERSION = SchemaVersion.DRAFT_2020_12;
    private static final SchemaGenerator GENERATOR = buildGenerator();

    // JSON Schema keyword constants (from victools SchemaKeyword enum)
    public static final String KEY_TYPE = SchemaKeyword.TAG_TYPE.forVersion(SCHEMA_VERSION);
    public static final String KEY_PROPERTIES = SchemaKeyword.TAG_PROPERTIES.forVersion(SCHEMA_VERSION);
    public static final String KEY_REQUIRED = SchemaKeyword.TAG_REQUIRED.forVersion(SCHEMA_VERSION);
    public static final String KEY_ADDITIONAL_PROPERTIES =
        SchemaKeyword.TAG_ADDITIONAL_PROPERTIES.forVersion(SCHEMA_VERSION);
    public static final String KEY_CONST = SchemaKeyword.TAG_CONST.forVersion(SCHEMA_VERSION);
    public static final String KEY_DESCRIPTION = SchemaKeyword.TAG_DESCRIPTION.forVersion(SCHEMA_VERSION);
    public static final String KEY_ENUM = SchemaKeyword.TAG_ENUM.forVersion(SCHEMA_VERSION);
    public static final String KEY_DEFAULT = SchemaKeyword.TAG_DEFAULT.forVersion(SCHEMA_VERSION);
    public static final String KEY_FORMAT = SchemaKeyword.TAG_FORMAT.forVersion(SCHEMA_VERSION);
    public static final String KEY_PATTERN = SchemaKeyword.TAG_PATTERN.forVersion(SCHEMA_VERSION);
    public static final String KEY_TITLE = SchemaKeyword.TAG_TITLE.forVersion(SCHEMA_VERSION);
    public static final String KEY_ITEMS = SchemaKeyword.TAG_ITEMS.forVersion(SCHEMA_VERSION);
    public static final String KEY_ALLOF = SchemaKeyword.TAG_ALLOF.forVersion(SCHEMA_VERSION);
    public static final String KEY_ANYOF = SchemaKeyword.TAG_ANYOF.forVersion(SCHEMA_VERSION);
    public static final String KEY_ONEOF = SchemaKeyword.TAG_ONEOF.forVersion(SCHEMA_VERSION);
    public static final String KEY_NOT = SchemaKeyword.TAG_NOT.forVersion(SCHEMA_VERSION);
    public static final String KEY_IF = SchemaKeyword.TAG_IF.forVersion(SCHEMA_VERSION);
    public static final String KEY_THEN = SchemaKeyword.TAG_THEN.forVersion(SCHEMA_VERSION);
    public static final String KEY_ELSE = SchemaKeyword.TAG_ELSE.forVersion(SCHEMA_VERSION);

    // Schema type constants
    public static final String TYPE_OBJECT = SchemaKeyword.SchemaType.OBJECT.getSchemaKeywordValue();
    public static final String TYPE_STRING = SchemaKeyword.SchemaType.STRING.getSchemaKeywordValue();
    public static final String TYPE_INTEGER = SchemaKeyword.SchemaType.INTEGER.getSchemaKeywordValue();
    public static final String TYPE_NUMBER = SchemaKeyword.SchemaType.NUMBER.getSchemaKeywordValue();
    public static final String TYPE_BOOLEAN = SchemaKeyword.SchemaType.BOOLEAN.getSchemaKeywordValue();
    public static final String TYPE_ARRAY = SchemaKeyword.SchemaType.ARRAY.getSchemaKeywordValue();
    public static final String TYPE_NULL = SchemaKeyword.SchemaType.NULL.getSchemaKeywordValue();

    private JsonSchemaGenerator() {}

    private static SchemaGenerator buildGenerator() {
        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
            SCHEMA_VERSION, OptionPreset.PLAIN_JSON)
            .with(new JacksonModule())
            .with(new JakartaValidationModule())
            .with(new Swagger2Module())
            .with(Option.EXTRA_OPEN_API_FORMAT_VALUES,
                  Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT,
                  Option.INLINE_ALL_SCHEMAS)
            .without(Option.SCHEMA_VERSION_INDICATOR);
        return new SchemaGenerator(builder.build());
    }

    public static JsonNode generate(Class<?> contractClass) {
        JsonNode schema = GENERATOR.generateSchema(contractClass);
        return schema;
    }

    public static SchemaVersion getVersion() {
        return SCHEMA_VERSION;
    }
}
