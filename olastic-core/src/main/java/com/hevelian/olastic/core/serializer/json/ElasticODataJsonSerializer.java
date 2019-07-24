package com.hevelian.olastic.core.serializer.json;

import static com.hevelian.olastic.core.serializer.utils.SerializeUtils.getPropertyType;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.olingo.commons.api.Constants;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Linked;
import org.apache.olingo.commons.api.data.Operation;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.edm.EdmPrimitiveType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.commons.api.edm.EdmStructuredType;
import org.apache.olingo.commons.api.edm.provider.CsdlProperty;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.core.edm.EdmPropertyImpl;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.serializer.PrimitiveSerializerOptions;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.queryoption.SelectOption;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import org.apache.olingo.server.core.serializer.SerializerResultImpl;
import org.apache.olingo.server.core.serializer.json.ODataJsonSerializer;
import org.apache.olingo.server.core.serializer.utils.CircleStreamBuffer;
import org.apache.olingo.server.core.serializer.utils.ContentTypeHelper;
import org.apache.olingo.server.core.serializer.utils.ContextURLBuilder;
import org.apache.olingo.server.core.serializer.utils.ExpandSelectHelper;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/**
 * Custom implementation of {@link ODataJsonSerializer} to override some default
 * behavior. Some code duplication present here, because of bugs on Olingo
 * library. Remove code duplication when bugs will be fixed.
 * 
 * 
 * @author rdidyk
 */
public class ElasticODataJsonSerializer extends ODataJsonSerializer {

    // Because Constans.JSON_NULL = "odata.null" but should be like below
    private static final String JSON_NULL = "@odata.null";
    private final boolean isODataMetadataNone;
    private final boolean isODataMetadataFull;

    /**
     * Constructor to initialize content type.
     * 
     * @param contentType
     *            content type
     */
    public ElasticODataJsonSerializer(ContentType contentType) {
        super(contentType);
        isODataMetadataNone = ContentTypeHelper.isODataMetadataNone(contentType);
        isODataMetadataFull = ContentTypeHelper.isODataMetadataFull(contentType);
    }

    @Override
    protected void writeProperties(ServiceMetadata metadata, EdmStructuredType type,
            List<Property> properties, SelectOption select, JsonGenerator json, Linked linked, ExpandOption expand)
            throws IOException, SerializerException {
        boolean all = ExpandSelectHelper.isAll(select);
        Set<String> selected = all ? new HashSet<>()
                : ExpandSelectHelper.getSelectedPropertyNames(select.getSelectItems());
        Set<List<String>> expandedPaths = ExpandSelectHelper.getExpandedItemsPath(expand);
        for (Property property : properties) {
            String propertyName = property.getName();
            if (all || selected.contains(propertyName)) {
                EdmProperty edmProperty = type.getStructuralProperty(propertyName);
                if (edmProperty == null) {
                    edmProperty = new EdmPropertyImpl(metadata.getEdm(), new CsdlProperty()
                            .setType(getPropertyType(property.getValue())).setName(propertyName));
                }
                Set<List<String>> selectedPaths = all || edmProperty.isPrimitive() ? null
                        : ExpandSelectHelper.getSelectedPaths(select.getSelectItems(),
                                propertyName);
                writeProperty(metadata, edmProperty, property, selectedPaths, json, expandedPaths, linked, expand);
            }
        }
    }

    // Method was overridden because of issue
    // https://issues.apache.org/jira/browse/OLINGO-1071
    @Override
    public SerializerResult primitive(ServiceMetadata metadata, EdmPrimitiveType type,
            Property property, PrimitiveSerializerOptions options) throws SerializerException {
        OutputStream outputStream = null;
        SerializerException cachedException = null;
        try (JsonGenerator json = new JsonFactory().createGenerator(outputStream)) {
            ContextURL contextURL = getContextURL(options);
            CircleStreamBuffer buffer = new CircleStreamBuffer();
            outputStream = buffer.getOutputStream();
            json.writeStartObject();
            write(contextURL, json);
            write(metadata, json);
            write(property.getOperations(), json);

            if (property.isNull()) {
                json.writeFieldName(JSON_NULL);
                json.writeBoolean(true);
            } else {
                json.writeFieldName(Constants.VALUE);
                writePrimitive(type, property, options, json);
            }
            json.close();
            outputStream.close();
            return SerializerResultImpl.with().content(buffer.getInputStream()).build();
        } catch (final IOException e) {
            cachedException = new SerializerException(IO_EXCEPTION_TEXT, e,
                    SerializerException.MessageKeys.IO_EXCEPTION);
            throw cachedException;
        } catch (final EdmPrimitiveTypeException e) {
            cachedException = new SerializerException("Wrong value for property!", e,
                    SerializerException.MessageKeys.WRONG_PROPERTY_VALUE, property.getName(),
                    property.getValue().toString());
            throw cachedException;
        } finally {
            closeCircleStreamBufferOutput(outputStream, cachedException);
        }
    }

    /**
     * Gets and checks context URL.
     * 
     * @param options
     *            options for the serializer
     * @return context URL
     * @throws SerializerException
     *             if any error occurred
     */
    private ContextURL getContextURL(PrimitiveSerializerOptions options)
            throws SerializerException {
        ContextURL contextURL = options == null ? null : options.getContextURL();
        if (isODataMetadataNone) {
            return null;
        } else if (contextURL == null) {
            throw new SerializerException("ContextURL null!",
                    SerializerException.MessageKeys.NO_CONTEXT_URL);
        }
        return contextURL;
    }

    /**
     * Writes context URL into json.
     * 
     * @param contextURL
     *            context URL
     * @param json
     *            json generator
     * @throws IOException
     *             if any error occurred
     */
    private void write(ContextURL contextURL, JsonGenerator json) throws IOException {
        if (!isODataMetadataNone && contextURL != null) {
            json.writeStringField(Constants.JSON_CONTEXT,
                    ContextURLBuilder.create(contextURL).toASCIIString());
        }
    }

    /**
     * Writes metadata eTag into json.
     * 
     * @param metadata
     *            service metadata
     * @param json
     *            json generator
     * @throws IOException
     *             if any error occurred
     */
    private void write(ServiceMetadata metadata, JsonGenerator json) throws IOException {
        if (!isODataMetadataNone && metadata != null
                && metadata.getServiceMetadataETagSupport() != null
                && metadata.getServiceMetadataETagSupport().getMetadataETag() != null) {
            json.writeStringField(Constants.JSON_METADATA_ETAG,
                    metadata.getServiceMetadataETagSupport().getMetadataETag());
        }
    }

    /**
     * Writes operations into json.
     * 
     * @param operations
     *            operations list
     * @param json
     *            json generator
     * @throws IOException
     *             if any error occurred
     */
    private void write(final List<Operation> operations, final JsonGenerator json)
            throws IOException {
        if (isODataMetadataFull) {
            for (Operation operation : operations) {
                json.writeObjectFieldStart(operation.getMetadataAnchor());
                json.writeStringField(Constants.ATTR_TITLE, operation.getTitle());
                json.writeStringField(Constants.ATTR_TARGET, operation.getTarget().toASCIIString());
                json.writeEndObject();
            }
        }
    }

    /**
     * Writes primitive property into json.
     * 
     * @param type
     *            the EDM type
     * @param property
     *            property instance
     * @param options
     *            options for the serializer
     * @param json
     *            json generator
     * @throws EdmPrimitiveTypeException
     *             if any error occurred
     * @throws IOException
     *             if any error occurred
     * @throws SerializerException
     *             if any error occurred
     */
    private void writePrimitive(EdmPrimitiveType type, Property property,
            PrimitiveSerializerOptions options, JsonGenerator json)
            throws EdmPrimitiveTypeException, IOException, SerializerException {
        Boolean isNullable = options == null ? null : options.isNullable();
        Integer maxLength = options == null ? null : options.getMaxLength();
        Integer precision = options == null ? null : options.getPrecision();
        Integer scale = options == null ? null : options.getScale();
        Boolean isUnicode = options == null ? null : options.isUnicode();
        if (property.isPrimitive()) {
            writePrimitiveValue(property.getName(), type, property.asPrimitive(), isNullable,
                    maxLength, precision, scale, isUnicode, json);
        } else if (property.isGeospatial()) {
            throw new SerializerException("Property type not yet supported!",
                    SerializerException.MessageKeys.UNSUPPORTED_PROPERTY_TYPE, property.getName());
        } else if (property.isEnum()) {
            writePrimitiveValue(property.getName(), type, property.asEnum(), isNullable, maxLength,
                    precision, scale, isUnicode, json);
        } else {
            throw new SerializerException("Inconsistent property type!",
                    SerializerException.MessageKeys.INCONSISTENT_PROPERTY_TYPE, property.getName());
        }
    }

}
