package com.mtole.task.activity;

import com.mtole.task.activity.dto.ActivityStatsResponse;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.FacetOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ActivityEventRepositoryImpl implements ActivityEventRepositoryCustom {


    private final MongoTemplate mongoTemplate;

    public ActivityEventRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Page<ActivityEvent> search(Long userId, ActivityEventFilter filter, Pageable pageable) {

        // 1. Criterio base obligatorio: siempre filtramos por userId
        Criteria criteria = Criteria.where("userId").is(userId);

        // 2. Criterio opcional: solo añadir si está presente
        if (filter.resourceType() != null && !filter.resourceType().isBlank()) {
            criteria.and("resourceType").is(filter.resourceType());
        }
        if (filter.from() != null && filter.to() != null) {
            criteria.and("timestamp").gte(filter.from()).lte(filter.to());
        } else if (filter.from() != null) {
            criteria.and("timestamp").gte(filter.from());
        } else if (filter.to() != null) {
            criteria.and("timestamp").lte(filter.to());
        }
        if (filter.resourceId() != null) {
            criteria.and("resourceId").is(filter.resourceId());
        }
        // 3. Construir Query con criteria + pageable (paginación + sort)
        Query query = new Query(criteria).with(pageable);

        // 4. Ejecutar
        List<ActivityEvent> content = mongoTemplate.find(query, ActivityEvent.class);

        // 5. Para Page necesitas el total: count con la misma criteria pero SIN pageable
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ActivityEvent.class);

        return new PageImpl<>(content, pageable, total);
    }


    @Override
    public ActivityStatsResponse getStats(Long userId, ActivityStatsFilter filter) {

        // 1. Construir el Criteria del $match (mismo patrón que search())
        Criteria criteria = Criteria.where("userId").is(userId);
        if (filter.from() != null && filter.to() != null) {
            criteria.and("timestamp").gte(filter.from()).lte(filter.to());
        } else if (filter.from() != null) {
            criteria.and("timestamp").gte(filter.from());
        } else if (filter.to() != null) {
            criteria.and("timestamp").lte(filter.to());
        }

        // TODO: construir el pipeline con $match + $facet
        // 2. Construir el $facet con tres sub-pipelines
        FacetOperation facet = Aggregation.facet(
                        Aggregation.group()
                                .count().as("total")
                                .min("timestamp").as("firstEvent")
                                .max("timestamp").as("lastEvent")
                ).as("totals")
                .and(
                        Aggregation.group("action").count().as("count")
                ).as("byAction")
                .and(
                        Aggregation.group("resourceType").count().as("count")
                ).as("byResourceType");

        // TODO: ejecutar
        // 3. Pipeline completo: match + facet
        Aggregation pipeline = Aggregation.newAggregation(
                Aggregation.match(criteria),
                facet
        );

        // 4. Ejecutar y obtener un Document genérico
        AggregationResults<Document> results = mongoTemplate.aggregate(
                pipeline,
                "activity_events",
                Document.class
        );
        Document raw = results.getUniqueMappedResult();


        // TODO: parsear el Document a ActivityStatsResponse
        // 5. Parsear el Document
        if (raw == null) {
            return new ActivityStatsResponse(0, null, null, Map.of(), Map.of());
        }

        // 5a. Totals (siempre array, puede estar vacío)
        List<Document> totalsList = raw.getList("totals", Document.class);
        long totalEvents = 0;
        Instant firstEvent = null;
        Instant lastEvent = null;
        if (!totalsList.isEmpty()) {
            Document totals = totalsList.get(0);
            Number totalNum = totals.get("total", Number.class);
            totalEvents = totalNum != null ? totalNum.longValue() : 0;
            firstEvent = toInstant(totals.get("firstEvent"));
            lastEvent = toInstant(totals.get("lastEvent"));
        }

        // 5b. byAction
        Map<String, Long> eventsByAction = new HashMap<>();
        for (Document d : raw.getList("byAction", Document.class)) {
            String action = d.getString("_id");
            Number count = d.get("count", Number.class);
            if (action != null && count != null) {
                eventsByAction.put(action, count.longValue());
            }
        }

        // 5c. byResourceType
        Map<String, Long> eventsByResourceType = new HashMap<>();
        for (Document d : raw.getList("byResourceType", Document.class)) {
            String resourceType = d.getString("_id");
            Number count = d.get("count", Number.class);
            if (resourceType != null && count != null) {
                eventsByResourceType.put(resourceType, count.longValue());
            }
        }

        return new ActivityStatsResponse(
                totalEvents,
                firstEvent,
                lastEvent,
                eventsByAction,
                eventsByResourceType
        );
    }

    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof Instant i) return i;
        return null;
    }
}


