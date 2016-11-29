package com.hevelian.olastic.core.processors.data;

import com.hevelian.olastic.core.elastic.ESClient;
import com.hevelian.olastic.core.elastic.ElasticConstants;
import com.hevelian.olastic.core.elastic.builders.ESQueryBuilder;
import com.hevelian.olastic.core.elastic.pagination.Pagination;
import com.hevelian.olastic.core.elastic.pagination.Sort;
import com.hevelian.olastic.core.processors.ElasticSearchExpressionVisitor;
import com.hevelian.olastic.core.util.Util;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.data.*;
import org.apache.olingo.commons.api.edm.*;
import org.apache.olingo.commons.api.edm.constants.EdmTypeKind;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.*;
import org.apache.olingo.server.api.uri.queryoption.*;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;
import org.apache.olingo.server.api.uri.queryoption.expression.Member;
import org.apache.olingo.server.core.uri.UriResourceNavigationPropertyImpl;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;

import java.util.*;

/**
 * This class provides high-level methods for retrieving and converting the data.
 * It contains all the metadata and request parameters needed for requesting and serializing the data.
 */
@Log4j2
public class DataRetriever {
    public final static String SELECT_ITEMS_SEPARATOR = ",";
    protected UriInfo uriInfo;
    protected OData odata;
    protected Client client;
    protected String rawBaseUri;
    protected ServiceMetadata serviceMetadata;
    protected ContentType responseFormat;

    /**
     * Encapsulates Query builder needed for getting the data from ES and last URI entity set, needed for serializing the response.
     */
    protected class QueryWithEntitySet {
        private EdmEntitySet entitySet;
        private ESQueryBuilder query;

        public QueryWithEntitySet(EdmEntitySet entitySet, ESQueryBuilder query) {
            this.entitySet = entitySet;
            this.query = query;
        }

        public EdmEntitySet getEntitySet() {
            return entitySet;
        }

        public ESQueryBuilder getQuery() {
            return query;
        }
    }

    /**
     * Fully initializes {@link DataRetriever}.
     * @param uriInfo uriInfo object
     * @param odata odata instance
     * @param client ES raw client
     * @param rawBaseUri war base uri
     * @param serviceMetadata service metadata
     * @param responseFormat response format
     */
    public DataRetriever(UriInfo uriInfo, OData odata, Client client, String rawBaseUri,
                         ServiceMetadata serviceMetadata, ContentType responseFormat) {
        this.uriInfo = uriInfo;
        this.odata = odata;
        this.client = client;
        this.rawBaseUri = rawBaseUri;
        this.serviceMetadata = serviceMetadata;
        this.responseFormat = responseFormat;
    }

    /**
     * Retrieves and serializes the data.
     *
     * @return serialized data
     * @throws ODataApplicationException
     * @throws SerializerException
     */
    public SerializerResult getSerializedData() throws ODataApplicationException, SerializerException {
        FilterOption filterOption = uriInfo.getFilterOption();
        QueryBuilder queryBuilder = null;
        if (filterOption != null) {
            Expression expression = filterOption.getExpression();
            try {
                queryBuilder = (QueryBuilder) expression.accept(new ElasticSearchExpressionVisitor());
            } catch (ExpressionVisitException e) {
                log.debug(e);
            }
        }
        QueryWithEntitySet queryWithEntitySet = getQueryWithEntitySet();
        EdmEntitySet responseEdmEntitySet = queryWithEntitySet.getEntitySet();
        responseEdmEntitySet.getEntityType().getNamespace();
        ESQueryBuilder esQueryBuilder = queryWithEntitySet.getQuery();
        SearchResponse response = getData(esQueryBuilder, queryBuilder);

        return serialize(response, responseEdmEntitySet);

    }

    /**
     * Serializes response from ES.
     *
     * @param response             ES response
     * @param responseEdmEntitySet entitySet
     * @return serialized data
     * @throws SerializerException
     * @throws ODataApplicationException
     */
    protected SerializerResult serialize(SearchResponse response, EdmEntitySet responseEdmEntitySet) throws SerializerException, ODataApplicationException {
        EntityCollection entities = new EntityCollection();
        List<Entity> productList = entities.getEntities();
        for (SearchHit hit : response.getHits()) {
            Entity e = new Entity();
            e.setId(Util.createId(responseEdmEntitySet.getName(), hit.getId()));
            addProperty(e, ElasticConstants.ID_FIELD_NAME, hit.getId(), responseEdmEntitySet);

            for (Map.Entry<String, Object> s : hit.getSource().entrySet()) {
                addProperty(e, s.getKey(), s.getValue(), responseEdmEntitySet);
            }
            productList.add(e);
        }

        if (isCount()) {
            entities.setCount((int) response.getHits().getTotalHits());
        }
        ContextURL contextUrl = ContextURL.with().entitySet(responseEdmEntitySet).selectList(StringUtils.join(getSelectList(), SELECT_ITEMS_SEPARATOR)).build();
        final String id = rawBaseUri + "/" + responseEdmEntitySet.getName();
        SelectOption selectOption = uriInfo.getSelectOption();
        EntityCollectionSerializerOptions opts = EntityCollectionSerializerOptions.with().contextURL(contextUrl).id(id)
                .count(uriInfo.getCountOption()).select(selectOption).build();
        EdmEntityType edmEntityType = responseEdmEntitySet.getEntityType();

        ODataSerializer serializer = odata.createSerializer(responseFormat);
        return serializer.entityCollection(serviceMetadata, edmEntityType,
                entities, opts);
    }

    /**
     * Returns the list of fields for which the data should be retrieved.
     *
     * @return fields
     */
    protected List<String> getSelectList() {
        List<String> result = new ArrayList<>();
        SelectOption selectOption = uriInfo.getSelectOption();
        if (selectOption != null) {
            List<SelectItem> selectItems = selectOption.getSelectItems();
            for (SelectItem selectItem : selectItems) {
                List<UriResource> selectParts = selectItem.getResourcePath().getUriResourceParts();
                String fieldName = selectParts.get(selectParts.size() - 1).getSegmentValue();
                result.add(fieldName);
            }
        }
        return result;
    }

    /**
     * Builds the query builder for requesting the data and entity set for serializing.
     *
     * @return Query builder and entity set
     * @throws ODataApplicationException
     */
    protected QueryWithEntitySet getQueryWithEntitySet() throws ODataApplicationException {
        List<UriResource> resourceParts = uriInfo.getUriResourceParts();
        UriResourceEntitySet firstUriResourceEntitySet;
        try {
            firstUriResourceEntitySet = (UriResourceEntitySet) resourceParts.get(0);
        } catch (ClassCastException e) {
            throw new ODataApplicationException("Only EntitySet is supported",
                    HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), Locale.ROOT);
        }

        EdmEntitySet responseEdmEntitySet = firstUriResourceEntitySet.getEntitySet();
        ESQueryBuilder parentChildQueryBuilder = new ESQueryBuilder();
        for (int i = 0; i < getUsefulPartsSize(); i++) {
            UriResource segment = resourceParts.get(i);
            if (segment.getKind() == UriResourceKind.primitiveProperty) {
                break;
            }
            if (segment.getKind() == UriResourceKind.navigationProperty) {
                UriResourceNavigation uriResourceNavigation = (UriResourceNavigation) segment;
                EdmNavigationProperty edmNavigationProperty = uriResourceNavigation.getProperty();
                responseEdmEntitySet = Util.getNavigationTargetEntitySet(responseEdmEntitySet,
                        edmNavigationProperty);
            } else if (segment.getKind() != UriResourceKind.entitySet) {
                throw new ODataApplicationException("Not supported",
                        HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), Locale.ROOT);
            }
            buildQuery(parentChildQueryBuilder, i);
        }
        parentChildQueryBuilder.setEsType(responseEdmEntitySet.getName());
        return new QueryWithEntitySet(responseEdmEntitySet, parentChildQueryBuilder);
    }

    /**
     * Builds query to elasticsearch using given part of the url.
     * @param query query builder that should be updated with a query for given part of the url
     * @param urlPartIndex index of the part of url for which query should be added
     * @throws ODataApplicationException
     */
    private void buildQuery(ESQueryBuilder query, int urlPartIndex) throws ODataApplicationException {
        List<UriResource> resourceParts = uriInfo.getUriResourceParts();
        UriResource segment = resourceParts.get(urlPartIndex);
        boolean isLast = urlPartIndex == getUsefulPartsSize() - 1;
        String type = ((UriResourcePartTyped) segment).getType().getName();
        List<String> ids = collectIds(segment);
        if (!isLast) {
            UriResource nextSegment = resourceParts.get(urlPartIndex + 1);
            if (((UriResourceNavigationPropertyImpl) nextSegment).getProperty().isCollection()) {
                query.addParentQuery(type, ids);
            } else {
                query.addChildQuery(type, ids);
            }
        } else {
            query.addIdsQuery(type, ids);
        }
    }

    /**
     * Returns the size of the url parts that are involved in the query building.
     *
     * @return useful url parts size
     */
    protected int getUsefulPartsSize() {
        List<UriResource> resourceParts = uriInfo.getUriResourceParts();
        return resourceParts.size();
    }

    /**
     * Retrieves ids from uri resource part.
     *
     * @param segment uri resource part
     * @return ids list
     */
    protected List<String> collectIds(UriResource segment) throws ODataApplicationException {
        List<String> ids = new ArrayList<>();
        List<UriParameter> keyPredicates;
        if (segment instanceof UriResourceNavigation) {
            keyPredicates = ((UriResourceNavigation) segment).getKeyPredicates();
        } else {
            keyPredicates = ((UriResourceEntitySet) segment).getKeyPredicates();
        }
        if (keyPredicates.size() > 1) {
            throw new ODataApplicationException("Composite Keys are not supported",
                    HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), Locale.ROOT);
        }
        for (UriParameter param : keyPredicates) {
            ids.add(param.getText().replaceAll("\'", ""));
        }
        return ids;
    }

    /**
     * Gets the data from ES.
     *
     * @param query  query builder
     * @param filter raw ES query with filter
     * @return ES response
     */
    protected SearchResponse getData(ESQueryBuilder query, QueryBuilder filter) {
        SearchResponse response = ESClient.executeRequest("authors", query.getEsType(), client, //TODO get index from metadata
                new BoolQueryBuilder().filter(query.getQuery())
                        .filter(filter == null ? new MatchAllQueryBuilder() : filter),
                getPagination(), getSelectList());

        return response;
    }

    /**
     * Checks if URI has count option.
     *
     * @return true if there is count option in the url
     */
    protected boolean isCount() {
        // handle $count: always return the original number of entities, without
        // considering $top and $skip
        boolean isCount = false;
        CountOption countOption = uriInfo.getCountOption();
        if (countOption != null) {
            isCount = countOption.getValue();
        }
        return isCount;
    }

    /**
     * Returns pagination data.
     *
     * @return pagination
     */
    protected Pagination getPagination() {
        int skipNumber = Pagination.SKIP_DEFAULT;
        SkipOption skipOption = uriInfo.getSkipOption();
        if (skipOption != null) {
            skipNumber = skipOption.getValue();
        }
        int topNumber = Pagination.TOP_DEFAULT;
        TopOption topOption = uriInfo.getTopOption();
        if (topOption != null) {
            topNumber = topOption.getValue();
        }
        OrderByOption orderByOption = uriInfo.getOrderByOption();
        List<Sort> orderBy = new ArrayList<>();
        if (orderByOption != null) {
            List<OrderByItem> orderItemList = orderByOption.getOrders();
            for (OrderByItem orderByItem : orderItemList) {
                Expression expression = orderByItem.getExpression();
                if (expression instanceof Member) {
                    UriInfoResource resourcePath = ((Member) expression).getResourcePath();
                    UriResource oUriResource = resourcePath.getUriResourceParts().get(0);
                    if (oUriResource instanceof UriResourcePrimitiveProperty) {
                        EdmProperty edmProperty = ((UriResourcePrimitiveProperty) oUriResource)
                                .getProperty();
                        orderBy.add(new Sort(edmProperty.getName(), orderByItem.isDescending() ? Sort.Direction.DESC : Sort.Direction.ASC));
                    }
                }
            }
        }
        return new Pagination(topNumber, skipNumber, orderBy);
    }

    private void addProperty(Entity e, String name, Object value, EdmEntitySet entitySet) {
        if (value instanceof List) {
            e.addProperty(createPropertyList(name, (List<Object>)value, entitySet));
        } else if (value instanceof Map) {
            e.addProperty(createComplexProperty(name, (Map<String, Object>) value));
        } else {
            e.addProperty(createPrimitiveProperty(name, value));
        }
    }

    private Property createPrimitiveProperty(String name, Object value) {
        return new Property(null, name, ValueType.PRIMITIVE, value);
    }

    private Property createComplexProperty(String name, Map<String, Object> value) {
        ComplexValue complexValue = createComplexValue(value);
        return new Property(null, name, ValueType.COMPLEX, complexValue);
    }

    private Property createPropertyList(String name, List<Object> valueObject, EdmEntitySet entitySet) {
        ValueType valueType;
        EdmTypeKind propertyKind = entitySet.getEntityType().getProperty(name).getType().getKind();
        if (propertyKind == EdmTypeKind.COMPLEX) {
            valueType = ValueType.COLLECTION_COMPLEX;
        } else {
            valueType = ValueType.COLLECTION_PRIMITIVE;
        }
        List<Object> properties = new ArrayList<>();
        for (Object value : valueObject) {
            if (value instanceof Map) {
                properties.add(createComplexValue((Map<String, Object>) value));
            } else {
                properties.add(createPrimitiveProperty(name, value));
            }
        }
        return new Property(null, name, valueType, properties);
    }

    private ComplexValue createComplexValue(Map<String, Object> complexObject) {
        ComplexValue complexValue = new ComplexValue();
        for (Map.Entry<String, Object> entry : complexObject.entrySet()) {
            complexValue.getValue().add(new Property(null, entry.getKey(), ValueType.PRIMITIVE, entry.getValue()));
        }
        return complexValue;
    }

}