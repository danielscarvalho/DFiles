package com.dfiles.service;

/** A column's inferred storage category, independent of any particular SQL dialect's actual
 * type-name spelling (that mapping lives in {@link SqlDialect#typeName}). */
enum SqlColumnType {
    INTEGER, REAL, TEXT
}
