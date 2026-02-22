package com.aleksandarparipovic.marel_app.search;

import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@SuppressWarnings({"unchecked", "rawtypes"})
public class SearchSpecification<T> implements Specification<T> {

    private final SearchRequest request;
    private final EntityFieldMapper<T> fieldMapper;

    public SearchSpecification(SearchRequest request, EntityFieldMapper<T> fieldMapper) {
        this.request = request;
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper is required");
    }

    @Override
    public Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        JoinManager<T> joinManager = new JoinManager<>(root);
        List<Predicate> predicates = new ArrayList<>();

        for (SearchRequest.FilterField filter : safeFilters()) {
            if (!isValidFilter(filter) || isBlankValue(filter.getValue())) {
                continue;
            }

            predicates.add(buildPredicate(filter, root, cb, joinManager));
        }

        if (hasText(globalSearch())) {
            String likePattern = "%" + globalSearch().toLowerCase(Locale.ROOT) + "%";
            List<Predicate> globalPredicates = fieldMapper.getGlobalSearchFields().stream()
                    .map(fieldName -> {
                        Path<?> path = fieldMapper.resolvePath(fieldName, root, cb, joinManager);
                        return cb.like(cb.lower(path.as(String.class)), likePattern);
                    })
                    .toList();

            if (!globalPredicates.isEmpty()) {
                predicates.add(cb.or(globalPredicates.toArray(Predicate[]::new)));
            }
        }

        if (query != null) {
            query.distinct(true);
        }

        return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
    }

    private Predicate buildPredicate(
            SearchRequest.FilterField filter,
            Root<T> root,
            CriteriaBuilder cb,
            JoinManager<T> joinManager
    ) {
        return switch (filter.getOperator()) {
            case CONTAINS_DATE -> containsDatePredicate(filter, root, cb, joinManager);
            case BETWEEN -> betweenPredicate(filter, root, cb, joinManager);
            default -> simplePredicate(filter, root, cb, joinManager);
        };
    }

    private Predicate simplePredicate(
            SearchRequest.FilterField filter,
            Root<T> root,
            CriteriaBuilder cb,
            JoinManager<T> joinManager
    ) {
        Path<?> path = fieldMapper.resolvePath(filter.getField(), root, cb, joinManager);
        Object rawValue = filter.getValue();

        return switch (filter.getOperator()) {
            case EQ -> cb.equal(path, convertValue(rawValue, path.getJavaType()));
            case NE -> cb.notEqual(path, convertValue(rawValue, path.getJavaType()));
            case LIKE -> cb.like(cb.lower(path.as(String.class)), "%" + rawValue.toString().toLowerCase(Locale.ROOT) + "%");
            case STARTS_WITH -> cb.like(cb.lower(path.as(String.class)), rawValue.toString().toLowerCase(Locale.ROOT) + "%");
            case ENDS_WITH -> cb.like(cb.lower(path.as(String.class)), "%" + rawValue.toString().toLowerCase(Locale.ROOT));
            case IN -> {
                Collection<?> values = toCollection(rawValue);
                if (values.isEmpty()) {
                    yield cb.conjunction();
                }

                Collection<?> convertedValues = values.stream()
                        .map(v -> convertValue(v, path.getJavaType()))
                        .toList();

                yield path.in(convertedValues);
            }
            case GT -> compare(path, rawValue, cb::greaterThan);
            case GTE -> compare(path, rawValue, cb::greaterThanOrEqualTo);
            case LT -> compare(path, rawValue, cb::lessThan);
            case LTE -> compare(path, rawValue, cb::lessThanOrEqualTo);
            default -> throw new IllegalArgumentException("Unsupported operator: " + filter.getOperator());
        };
    }

    private Predicate containsDatePredicate(
            SearchRequest.FilterField filter,
            Root<T> root,
            CriteriaBuilder cb,
            JoinManager<T> joinManager
    ) {
        String[] fields = filter.getField().split(":");
        if (fields.length != 2) {
            throw new IllegalArgumentException("CONTAINS_DATE requires field format 'startField:endField'");
        }

        LocalDate date = (LocalDate) convertValue(filter.getValue(), LocalDate.class);
        Expression<LocalDate> start = fieldMapper.resolvePath(fields[0].trim(), root, cb, joinManager).as(LocalDate.class);
        Expression<LocalDate> end = fieldMapper.resolvePath(fields[1].trim(), root, cb, joinManager).as(LocalDate.class);

        return cb.and(
                cb.lessThanOrEqualTo(start, date),
                cb.or(cb.isNull(end), cb.greaterThanOrEqualTo(end, date))
        );
    }

    private Predicate betweenPredicate(
            SearchRequest.FilterField filter,
            Root<T> root,
            CriteriaBuilder cb,
            JoinManager<T> joinManager
    ) {
        Path<?> path = fieldMapper.resolvePath(filter.getField(), root, cb, joinManager);
        Collection<?> values = toCollection(filter.getValue());

        if (values.size() != 2) {
            throw new IllegalArgumentException("BETWEEN requires exactly two values");
        }

        Iterator<?> iterator = values.iterator();
        Comparable min = (Comparable) convertValue(iterator.next(), path.getJavaType());
        Comparable max = (Comparable) convertValue(iterator.next(), path.getJavaType());

        if (min.compareTo(max) > 0) {
            Comparable tmp = min;
            min = max;
            max = tmp;
        }

        Expression<? extends Comparable> comparablePath = (Expression<? extends Comparable>) path.as((Class) wrap(path.getJavaType()));
        return cb.and(
                cb.greaterThanOrEqualTo(comparablePath, min),
                cb.lessThanOrEqualTo(comparablePath, max)
        );
    }

    private Predicate compare(
            Path<?> path,
            Object rawValue,
            ComparisonBuilder comparisonBuilder
    ) {
        Class<?> targetType = wrap(path.getJavaType());
        if (!Comparable.class.isAssignableFrom(targetType)) {
            throw new IllegalArgumentException("Field is not comparable: " + path.getAlias());
        }

        Comparable value = (Comparable) convertValue(rawValue, targetType);
        Expression<? extends Comparable> comparablePath = (Expression<? extends Comparable>) path.as((Class) targetType);
        return comparisonBuilder.build(comparablePath, value);
    }

    private List<SearchRequest.FilterField> safeFilters() {
        if (request == null || request.getFilters() == null) {
            return Collections.emptyList();
        }
        return request.getFilters();
    }

    private String globalSearch() {
        return request == null ? null : request.getGlobalSearch();
    }

    private boolean isValidFilter(SearchRequest.FilterField filter) {
        return filter != null
                && hasText(filter.getField())
                && filter.getOperator() != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isBlankValue(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private Collection<?> toCollection(Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }

        if (rawValue instanceof Collection<?> collection) {
            return collection;
        }

        if (rawValue.getClass().isArray()) {
            return Arrays.asList((Object[]) rawValue);
        }

        if (rawValue instanceof String text && text.contains(",")) {
            return Arrays.stream(text.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }

        return List.of(rawValue);
    }

    private Object convertValue(Object rawValue, Class<?> targetType) {
        if (rawValue == null) {
            return null;
        }

        Class<?> wrapped = wrap(targetType);
        if (wrapped.isInstance(rawValue)) {
            return rawValue;
        }

        String value = rawValue.toString().trim();
        if (wrapped == String.class) {
            return value;
        }
        if (wrapped == Long.class) {
            return rawValue instanceof Number n ? n.longValue() : Long.parseLong(value);
        }
        if (wrapped == Integer.class) {
            return rawValue instanceof Number n ? n.intValue() : Integer.parseInt(value);
        }
        if (wrapped == Double.class) {
            return rawValue instanceof Number n ? n.doubleValue() : Double.parseDouble(value);
        }
        if (wrapped == Float.class) {
            return rawValue instanceof Number n ? n.floatValue() : Float.parseFloat(value);
        }
        if (wrapped == BigDecimal.class) {
            return rawValue instanceof BigDecimal b ? b : new BigDecimal(value);
        }
        if (wrapped == BigInteger.class) {
            return rawValue instanceof BigInteger b ? b : new BigInteger(value);
        }
        if (wrapped == Boolean.class) {
            return rawValue instanceof Boolean b ? b : Boolean.parseBoolean(value);
        }
        if (wrapped == LocalDate.class) {
            return rawValue instanceof LocalDate d ? d : LocalDate.parse(value);
        }
        if (wrapped == LocalDateTime.class) {
            return rawValue instanceof LocalDateTime d ? d : LocalDateTime.parse(value);
        }
        if (wrapped == OffsetDateTime.class) {
            return rawValue instanceof OffsetDateTime d ? d : OffsetDateTime.parse(value);
        }
        if (wrapped == UUID.class) {
            return rawValue instanceof UUID uuid ? uuid : UUID.fromString(value);
        }
        if (Enum.class.isAssignableFrom(wrapped)) {
            return Enum.valueOf((Class<? extends Enum>) wrapped, value.toUpperCase(Locale.ROOT));
        }

        return rawValue;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    @FunctionalInterface
    private interface ComparisonBuilder {
        Predicate build(Expression<? extends Comparable> path, Comparable value);
    }
}
