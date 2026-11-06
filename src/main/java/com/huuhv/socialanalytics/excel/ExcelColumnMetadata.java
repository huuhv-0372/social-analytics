package com.huuhv.socialanalytics.excel;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata các field có {@link ExcelColumn} của 1 class, đọc bằng Reflection một lần rồi cache theo class
 * (dùng chung cho importer và exporter, không reflect lại mỗi dòng).
 */
public final class ExcelColumnMetadata {

    private static final Map<Class<?>, List<FieldMeta>> CACHE = new ConcurrentHashMap<>();

    private ExcelColumnMetadata() {
    }

    /**
     * @param field field đã {@code setAccessible(true)}
     */
    public record FieldMeta(Field field, String name, int order, Class<?> type) {
    }

    /**
     * Field có {@link ExcelColumn} (gồm cả của class cha), sắp xếp theo {@code order}. Danh sách không sửa được.
     *
     * @throws IllegalArgumentException khi class không có cột nào hoặc trùng tên cột (lỗi lập trình)
     */
    public static List<FieldMeta> of(Class<?> type) {
        return CACHE.computeIfAbsent(type, ExcelColumnMetadata::scan);
    }

    private static List<FieldMeta> scan(Class<?> type) {
        List<FieldMeta> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                ExcelColumn column = field.getAnnotation(ExcelColumn.class);
                if (column == null || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = column.name().strip();
                if (name.isEmpty() || !names.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "Blank or duplicate @ExcelColumn name '" + name + "' in " + type.getName());
                }
                field.setAccessible(true);
                fields.add(new FieldMeta(field, name, column.order(), field.getType()));
            }
        }
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("No @ExcelColumn field in " + type.getName());
        }
        fields.sort(Comparator.comparingInt(FieldMeta::order));
        return List.copyOf(fields);
    }
}
