package com.aleksandarparipovic.marel_app.search;

import lombok.Data;

import java.util.List;

@Data
public class SearchRequest {
    private SearchRequest.Pagination pagination;
    private List<SearchRequest.SortField> sort;
    private List<SearchRequest.FilterField> filters;
    private String globalSearch;

    // ---------- nested DTOs ----------

    @Data
    public static class Pagination {
        private int page;
        private int size;
    }

    @Data
    public static class SortField {
        private String field;     // column name
        private SearchRequest.Direction direction;  // true = DESC, false = ASC
    }

    @Data
    public static class FilterField {
        private String field;     // column name
        private SearchRequest.Operator operator;
        private Object value;  // always string from frontend
    }

    public enum Direction {
        ASC,
        DESC
    }

    public enum Operator {
        EQ, NE, LIKE, STARTS_WITH, ENDS_WITH,
        IN, GT, GTE, LT, LTE, CONTAINS_DATE, BETWEEN
    }
}
