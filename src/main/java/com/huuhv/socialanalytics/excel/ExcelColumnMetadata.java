package com.huuhv.socialanalytics.excel;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata các cột Excel của 1 DTO, đọc bằng Reflection <b>một lần</b> rồi cache theo class (dùng chung cho importer và
 * exporter, không reflect lại mỗi dòng / mỗi file).
 * <p>
 * Cột = field có {@link ExcelColumn} (gồm cả của class cha; với record là component). Giá trị đọc/ghi qua
 * <b>getter/setter</b> ({@link Method#invoke}), không truy cập field trực tiếp — DTO giữ được logic trong accessor và
 * không phải mở field private:
 * <ul>
 *     <li>record: accessor của component ({@code name()}); không có setter (chỉ export được).</li>
 *     <li>class: {@code getName()} (hoặc {@code isName()} cho {@code boolean}) và {@code setName(T)} public, đúng kiểu
 *     field — vd Lombok {@code @Getter @Setter}.</li>
 * </ul>
 * Thiếu getter → lỗi ngay khi lấy metadata; thiếu setter chỉ lỗi khi dùng để import ({@link ColumnMeta#write}).
 */
public final class ExcelColumnMetadata {

    private static final Map<Class<?>, List<ColumnMeta>> CACHE = new ConcurrentHashMap<>();

    private ExcelColumnMetadata() {
    }

    /**
     * 1 cột Excel.
     *
     * @param property tên field / component (trùng tên component của DTO request tương ứng — dùng map lỗi validate)
     * @param name     tên cột ở dòng header ({@link ExcelColumn#name()})
     * @param type     kiểu field — quyết định cách chuyển đổi giá trị ô
     * @param getter   accessor public, không tham số, trả đúng {@code type}
     * @param setter   {@code setX(type)} public; {@code null} khi DTO không cho ghi (record)
     */
    public record ColumnMeta(String property, String name, int order, Class<?> type, Method getter, Method setter) {

        public boolean isWritable() {
            return setter != null;
        }

        /** Gọi getter trên {@code target}. */
        public Object read(Object target) {
            return invoke(getter, target);
        }

        /**
         * Gọi setter trên {@code target}.
         *
         * @throws IllegalStateException khi cột không có setter (lỗi lập trình — importer đã kiểm tra trước)
         */
        public void write(Object target, Object value) {
            if (setter == null) {
                throw new IllegalStateException("No setter for column " + name);
            }
            invoke(setter, target, value);
        }

        private static Object invoke(Method method, Object target, Object... args) {
            try {
                return method.invoke(target, args);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access " + method, e);
            } catch (InvocationTargetException e) {
                // Lỗi bên trong accessor của DTO: giữ nguyên exception gốc làm cause
                throw new IllegalStateException("Accessor " + method.getName() + " failed", e.getCause());
            }
        }
    }

    /**
     * Cột của {@code type} theo {@code order}. Danh sách không sửa được, cùng 1 instance cho mọi lần gọi.
     *
     * @throws IllegalArgumentException khi class không có cột nào, trùng tên cột hoặc thiếu getter (lỗi lập trình)
     */
    public static List<ColumnMeta> of(Class<?> type) {
        return CACHE.computeIfAbsent(type, ExcelColumnMetadata::scan);
    }

    /** Tên cột Excel của field / component {@code property}; rỗng khi field đó không phải cột. */
    public static Optional<String> columnName(Class<?> type, String property) {
        return of(type).stream().filter(meta -> meta.property().equals(property)).map(ColumnMeta::name).findFirst();
    }

    /**
     * Mọi cột có setter — gọi trước khi dùng DTO để import.
     *
     * @throws IllegalArgumentException khi có cột không ghi được
     */
    public static void requireWritable(Class<?> type) {
        for (ColumnMeta meta : of(type)) {
            if (!meta.isWritable()) {
                throw new IllegalArgumentException("Column '" + meta.name() + "' of " + type.getName()
                        + " needs a public setter set" + capitalize(meta.property()) + "(" + meta.type().getSimpleName()
                        + ")");
            }
        }
    }

    private static List<ColumnMeta> scan(Class<?> type) {
        List<ColumnMeta> columns = new ArrayList<>();
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
                columns.add(new ColumnMeta(field.getName(), name, column.order(), field.getType(),
                        getter(type, field), setter(type, field)));
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("No @ExcelColumn field in " + type.getName());
        }
        columns.sort(Comparator.comparingInt(ColumnMeta::order));
        return List.copyOf(columns);
    }

    private static Method getter(Class<?> type, Field field) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(field.getName())) {
                    return accessible(component.getAccessor());
                }
            }
        }
        String suffix = capitalize(field.getName());
        Method getter = findPublic(type, "get" + suffix);
        if (getter == null && field.getType() == boolean.class) {
            getter = findPublic(type, "is" + suffix);
        }
        if (getter == null || getter.getReturnType() != field.getType()) {
            throw new IllegalArgumentException("Column field " + type.getName() + "." + field.getName()
                    + " needs a public getter get" + suffix + "() returning " + field.getType().getSimpleName());
        }
        return accessible(getter);
    }

    private static Method setter(Class<?> type, Field field) {
        if (type.isRecord()) {
            return null;
        }
        Method setter = findPublic(type, "set" + capitalize(field.getName()), field.getType());
        return setter == null ? null : accessible(setter);
    }

    private static Method findPublic(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            return Modifier.isStatic(method.getModifiers()) ? null : method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Method public của class không public (vd DTO lồng package-private) vẫn cần mở quyền để {@code invoke} từ package
     * {@code excel} — chỉ mở accessor đã chọn, không mở field.
     */
    private static Method accessible(Method method) {
        method.trySetAccessible();
        return method;
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
