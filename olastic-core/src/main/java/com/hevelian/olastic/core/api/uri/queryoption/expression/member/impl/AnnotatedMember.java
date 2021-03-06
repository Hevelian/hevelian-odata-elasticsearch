package com.hevelian.olastic.core.api.uri.queryoption.expression.member.impl;

import static com.hevelian.olastic.core.elastic.ElasticConstants.ID_FIELD_NAME;
import static com.hevelian.olastic.core.elastic.utils.ElasticUtils.addKeywordIfNeeded;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.existsQuery;
import static org.elasticsearch.index.query.QueryBuilders.idsQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;

import java.util.List;
import java.util.Locale;

import org.apache.olingo.commons.api.edm.EdmAnnotation;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.elasticsearch.index.query.QueryBuilder;

import com.hevelian.olastic.core.api.uri.queryoption.expression.member.ExpressionMember;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Represents expression member with type.
 *
 * @author Taras Kohut
 */
@AllArgsConstructor
@Getter
public abstract class AnnotatedMember extends BaseMember {

    private final String field;
    private final List<EdmAnnotation> annotations;

    /**
     * Gets query for equals and not equals operations.
     * 
     * @param expressionMember
     *            member with value
     * @return appropriate query
     * @throws ODataApplicationException
     *             if any error occurred during creating query
     */
    protected QueryBuilder getEqQuery(ExpressionMember expressionMember)
            throws ODataApplicationException {
        Object value = ((LiteralMember) expressionMember).getValue();
        if (getField().equals(ID_FIELD_NAME)) {
            if (value == null) {
                throw new ODataApplicationException("Id value can not be null",
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ROOT);
            }
            return idsQuery().addIds(value.toString());
        } else {
            String fieldName = addKeywordIfNeeded(getField(), getAnnotations());
            if (value == null) {
                return boolQuery().mustNot(existsQuery(fieldName));
            } else {
                return termQuery(fieldName, value);
            }
        }
    }

}
