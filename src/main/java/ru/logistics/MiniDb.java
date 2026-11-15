package ru.logistics;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Консольная файловая СУБД для учёта транспорта и перевозок. */
public final class MiniDb {
    private static final Path DATA = Paths.get("data");
    private final Database database;
    private final SqlEngine sql;

    MiniDb(Path directory) {
        database = new Database(directory);
        sql = new SqlEngine(database);
    }

    void initialize() throws Exception {
        database.load();
        database.createDefaults();
    }

    void execute(String command) {
        sql.execute(command);
    }

    public static void main(String[] args) {
        MiniDb app = new MiniDb(DATA);
        try {
            app.initialize();
            System.out.println("TransportDB. Введите SQL-команду; EXIT для выхода.");
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                System.out.print("db> ");
                String line = in.readLine();
                if (line == null || line.trim().equalsIgnoreCase("exit")) break;
                if (line.trim().isEmpty()) continue;
                try { app.execute(line); }
                catch (RuntimeException e) { System.out.println("Ошибка: " + e.getMessage()); }
            }
        } catch (Exception e) {
            System.err.println("Не удалось запустить базу: " + e.getMessage());
        }
    }

    private enum Type { INT, DOUBLE, STRING, DATE, BOOLEAN }

    /** Описание столбца таблицы. */
    private static final class Column implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name; final Type type;
        Column(String name, Type type) { this.name = name.toLowerCase(Locale.ROOT); this.type = type; }
    }

    /** Таблица с первичным ключом и строками в порядке вставки. */
    private static final class Table implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name; final List<Column> columns; final String primaryKey;
        final List<LinkedHashMap<String,Object>> rows = new ArrayList<LinkedHashMap<String,Object>>();
        Table(String name, List<Column> columns, String primaryKey) {
            this.name = name.toLowerCase(Locale.ROOT); this.columns = columns; this.primaryKey = primaryKey.toLowerCase(Locale.ROOT);
        }
        Column column(String name) {
            for (Column c : columns) if (c.name.equalsIgnoreCase(name)) return c;
            throw new IllegalArgumentException("Неизвестный столбец: " + name);
        }
    }

    /** Хранилище таблиц и вторичных индексов; изменения атомарно заменяют файл данных. */
    private static final class Database {
        private final Path directory; private final Map<String,Table> tables = new LinkedHashMap<String,Table>();
        private final Map<String,Map<Object,Set<Integer>>> indexes = new HashMap<String,Map<Object,Set<Integer>>>();
        Database(Path directory) { this.directory = directory; }
        @SuppressWarnings("unchecked") void load() throws Exception {
            Files.createDirectories(directory); Path file = directory.resolve("database.bin");
            if (Files.exists(file)) try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
                tables.putAll((Map<String,Table>) in.readObject());
            }
            Path indexFile = directory.resolve("indexes.bin");
            if (Files.exists(indexFile)) try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(indexFile))) {
                indexes.putAll((Map<String,Map<Object,Set<Integer>>>) in.readObject());
            }
            rebuildIndexes();
        }
        Table table(String name) {
            Table t = tables.get(name.toLowerCase(Locale.ROOT));
            if (t == null) throw new IllegalArgumentException("Таблица не найдена: " + name);
            return t;
        }
        void createDefaults() throws Exception {
            if (tables.containsKey("vehicles")) return;
            createTable(new Table("vehicles", cols("id INT, plate STRING, model STRING, capacity DOUBLE, active BOOLEAN"), "id"));
            createTable(new Table("shipments", cols("id INT, vehicle_id INT, destination STRING, cargo STRING, weight DOUBLE, ship_date DATE, delivered BOOLEAN"), "id"));
            persist();
        }
        void createTable(Table table) {
            if (tables.containsKey(table.name)) throw new IllegalArgumentException("Таблица уже существует: " + table.name);
            table.column(table.primaryKey); tables.put(table.name, table);
        }
        void dropTable(String name) {
            String key=name.toLowerCase(Locale.ROOT); if(!tables.containsKey(key))throw new IllegalArgumentException("Таблица не найдена: "+name);
            if("vehicles".equals(key)&&tables.containsKey("shipments")&&!table("shipments").rows.isEmpty())
                throw new IllegalArgumentException("Нельзя удалить vehicles: есть перевозки");
            tables.remove(key); for(String index:new ArrayList<String>(indexes.keySet()))if(index.startsWith(key+"."))indexes.remove(index);
            rebuildIndexes(); saveQuietly();
        }
        void insert(Table t, LinkedHashMap<String,Object> row) {
            Object id = row.get(t.primaryKey);
            if (id == null) throw new IllegalArgumentException("Первичный ключ не может быть пустым");
            for (Map<String,Object> old : t.rows) if (id.equals(old.get(t.primaryKey))) throw new IllegalArgumentException("Повтор первичного ключа: " + id);
            if (t.name.equals("shipments") && tables.containsKey("vehicles") && !containsId(table("vehicles"), row.get("vehicle_id")))
                throw new IllegalArgumentException("vehicle_id не ссылается на существующий транспорт");
            t.rows.add(row); rebuildIndexes(); saveQuietly();
        }
        private boolean containsId(Table t, Object id) { for (Map<String,Object> r : t.rows) if (Objects.equals(r.get(t.primaryKey), id)) return true; return false; }
        void createIndex(String name, String tableName, String column) {
            Table t = table(tableName); t.column(column);
            String key = indexKey(t.name, column);
            if (indexes.containsKey(key)) throw new IllegalArgumentException("Индекс для столбца уже создан");
            indexes.put(key, new HashMap<Object,Set<Integer>>()); rebuildIndexes(); saveQuietly();
            System.out.println("Индекс " + name + " создан.");
        }
        Set<Integer> indexed(Table t, String column, Object value) { return indexes.get(indexKey(t.name, column)) == null ? null : indexes.get(indexKey(t.name, column)).get(value); }
        private String indexKey(String table, String column) { return table + "." + column.toLowerCase(Locale.ROOT); }
        private void rebuildIndexes() {
            for (String key : new ArrayList<String>(indexes.keySet())) {
                String[] parts = key.split("\\.", 2); Table t = tables.get(parts[0]);
                if (t == null) { indexes.remove(key); continue; }
                Map<Object,Set<Integer>> map = new HashMap<Object,Set<Integer>>();
                for (int i=0;i<t.rows.size();i++) { Object value=t.rows.get(i).get(parts[1]); map.computeIfAbsent(value,k->new LinkedHashSet<Integer>()).add(i); }
                indexes.put(key,map);
            }
        }
        void persist() throws Exception {
            Files.createDirectories(directory); writeObject(directory.resolve("database.bin"), tables); writeObject(directory.resolve("indexes.bin"), indexes);
        }
        private void saveQuietly() { try { persist(); } catch (Exception e) { throw new IllegalStateException("Ошибка сохранения: " + e.getMessage(),e); } }
        private void writeObject(Path target, Object object) throws Exception {
            Path temp = target.resolveSibling(target.getFileName()+".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(temp))) { out.writeObject(object); }
            try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        }
        private static List<Column> cols(String spec) {
            List<Column> result = new ArrayList<Column>();
            for (String part : spec.split(",")) { String[] p=part.trim().split("\\s+"); result.add(new Column(p[0],Type.valueOf(p[1].toUpperCase(Locale.ROOT)))); }
            return result;
        }
    }

    /** Разбирает и выполняет ограниченное SQL-подмножество. */
    private static final class SqlEngine {
        private final Database db;
        SqlEngine(Database db) { this.db=db; }
        void execute(String input) {
            String q=input.trim().replaceAll(";+$", "").trim();
            if (q.matches("(?is)^CREATE\\s+INDEX\\b.*")) { createIndex(q); return; }
            if (q.matches("(?is)^CREATE\\s+TABLE\\b.*")) { createTable(q); return; }
            if (q.matches("(?is)^DROP\\s+TABLE\\b.*")) { dropTable(q); return; }
            if (q.matches("(?is)^INSERT\\s+INTO\\b.*")) { insert(q); return; }
            if (q.matches("(?is)^SELECT\\b.*")) { select(q); return; }
            if (q.matches("(?is)^UPDATE\\b.*")) { update(q); return; }
            if (q.matches("(?is)^DELETE\\b.*")) { delete(q); return; }
            throw new IllegalArgumentException("Поддерживаются CREATE/DROP TABLE, CREATE INDEX, INSERT, SELECT, UPDATE и DELETE");
        }
        private void createTable(String q) {
            Matcher m=Pattern.compile("(?is)^CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)$").matcher(q);
            if(!m.matches())throw syntax(); List<Column> columns=new ArrayList<Column>(); String primary=null;
            for(String definition:split(m.group(2))) {
                Matcher col=Pattern.compile("(?is)^\\s*(\\w+)\\s+(INT|INTEGER|DOUBLE|FLOAT|REAL|STRING|VARCHAR|DATE|BOOLEAN|BOOL)(?:\\s+(PRIMARY\\s+KEY))?\\s*$").matcher(definition);
                if(!col.matches())throw syntax(); String type=col.group(2).toUpperCase(Locale.ROOT);
                Type mapped=(type.equals("INTEGER")?Type.INT:(type.equals("FLOAT")||type.equals("REAL")?Type.DOUBLE:(type.equals("VARCHAR")?Type.STRING:(type.equals("BOOL")?Type.BOOLEAN:Type.valueOf(type)))));
                columns.add(new Column(col.group(1),mapped)); if(col.group(3)!=null){if(primary!=null)throw syntax();primary=col.group(1);}
            }
            if(primary==null)throw new IllegalArgumentException("Укажите PRIMARY KEY"); db.createTable(new Table(m.group(1),columns,primary)); db.saveQuietly(); System.out.println("Таблица создана.");
        }
        private void dropTable(String q) {
            Matcher m=Pattern.compile("(?is)^DROP\\s+TABLE\\s+(\\w+)$").matcher(q);
            if(!m.matches())throw syntax(); db.dropTable(m.group(1)); System.out.println("Таблица удалена.");
        }
        private void insert(String q) {
            Matcher m=Pattern.compile("(?is)^INSERT\\s+INTO\\s+(\\w+)\\s*(?:\\((.*?)\\))?\\s*VALUES\\s*(.+)$").matcher(q);
            if(!m.matches()) throw syntax(); Table t=db.table(m.group(1));
            List<String> names=m.group(2)==null?columnNames(t):split(m.group(2));
            for(String tuple:tuples(m.group(3))) { List<String> vals=split(tuple); if(names.size()!=vals.size()) throw syntax();
                LinkedHashMap<String,Object> row=new LinkedHashMap<String,Object>(); for(int i=0;i<names.size();i++) row.put(names.get(i).trim().toLowerCase(Locale.ROOT),parse(t.column(names.get(i).trim()),vals.get(i)));
                for(Column c:t.columns) row.putIfAbsent(c.name,null); db.insert(t,row);
            }
            System.out.println("Записи добавлены.");
        }
        private void select(String q) {
            Matcher m=Pattern.compile("(?is)^SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$").matcher(q);
            if(!m.matches()) throw syntax(); Table t=db.table(m.group(2)); Condition condition=m.group(3)==null?null:condition(t,m.group(3));
            List<String> selected=m.group(1).trim().equals("*")?columnNames(t):split(m.group(1)); List<Map<String,Object>> rows=matching(t,condition);
            for(String c:selected)t.column(c.trim()); print(selected,rows);
            if(condition!=null && db.indexed(t,condition.column,condition.value)!=null) System.out.println("(использован индекс: "+condition.column+")");
        }
        private void update(String q) {
            Matcher m=Pattern.compile("(?is)^UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$").matcher(q);
            if(!m.matches())throw syntax(); Table t=db.table(m.group(1)); Map<String,Object> changes=new LinkedHashMap<String,Object>();
            for(String assignment:split(m.group(2))) { String[] p=assignment.split("=",2); if(p.length!=2)throw syntax(); changes.put(p[0].trim().toLowerCase(Locale.ROOT),parse(t.column(p[0].trim()),p[1])); }
            Condition c=m.group(3)==null?null:condition(t,m.group(3)); int count=0;
            List<LinkedHashMap<String,Object>> backup=new ArrayList<LinkedHashMap<String,Object>>();
            for(LinkedHashMap<String,Object> row:t.rows)backup.add(new LinkedHashMap<String,Object>(row));
            try {
                for(Map<String,Object> row:matching(t,c)) { for(Map.Entry<String,Object> e:changes.entrySet()) row.put(e.getKey(),e.getValue()); count++; }
                validateKeys(t);
                if(t.name.equals("vehicles")&&tablesShipmentsExist())for(Map<String,Object> shipment:db.table("shipments").rows)
                    if(!containsVehicle(shipment.get("vehicle_id")))throw new IllegalArgumentException("Обновление нарушает связь shipments.vehicle_id");
                db.rebuildIndexes(); db.saveQuietly(); System.out.println("Обновлено строк: "+count);
            } catch(RuntimeException e) { t.rows.clear();t.rows.addAll(backup);db.rebuildIndexes();throw e; }
        }
        private boolean tablesShipmentsExist(){try{db.table("shipments");return true;}catch(IllegalArgumentException e){return false;}}
        private boolean containsVehicle(Object id){for(Map<String,Object> row:db.table("vehicles").rows)if(Objects.equals(row.get("id"),id))return true;return false;}
        private void delete(String q) {
            Matcher m=Pattern.compile("(?is)^DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$").matcher(q);
            if(!m.matches())throw syntax(); Table t=db.table(m.group(1)); Condition c=m.group(2)==null?null:condition(t,m.group(2));
            int before=t.rows.size(); t.rows.removeAll(matching(t,c)); db.rebuildIndexes(); db.saveQuietly(); System.out.println("Удалено строк: "+(before-t.rows.size()));
        }
        private void createIndex(String q) {
            Matcher m=Pattern.compile("(?is)^CREATE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)$").matcher(q);
            if(!m.matches())throw syntax(); db.createIndex(m.group(1),m.group(2),m.group(3));
        }
        private List<Map<String,Object>> matching(Table t,Condition c) {
            List<Map<String,Object>> found=new ArrayList<Map<String,Object>>(); Set<Integer> ids=null;
            if(c!=null && "=".equals(c.operator)) ids=db.indexed(t,c.column,c.value);
            for(int i=0;i<t.rows.size();i++) { if(ids!=null&&!ids.contains(i))continue; Map<String,Object> row=t.rows.get(i); if(c==null||c.test(row.get(c.column)))found.add(row); }
            return found;
        }
        private Condition condition(Table t,String expression) {
            Matcher m=Pattern.compile("(?is)^\\s*(\\w+)\\s*(=|<>|!=|>=|<=|>|<)\\s*(.+?)\\s*$").matcher(expression);
            if(!m.matches())throw syntax(); Column col=t.column(m.group(1)); return new Condition(col.name,m.group(2),parse(col,m.group(3)));
        }
        private static final class Condition {
            final String column,operator; final Object value;
            Condition(String c,String o,Object v){column=c;operator=o;value=v;}
            @SuppressWarnings({"unchecked","rawtypes"}) boolean test(Object actual) {
                if(actual==null)return false; int cmp=actual instanceof Comparable?((Comparable)actual).compareTo(value):actual.equals(value)?0:1;
                if("=".equals(operator))return cmp==0; if("!=".equals(operator)||"<>".equals(operator))return cmp!=0;
                if(">".equals(operator))return cmp>0; if("<".equals(operator))return cmp<0; if(">=".equals(operator))return cmp>=0; return cmp<=0;
            }
        }
        private static Object parse(Column c,String raw) {
            String s=raw.trim(); if(s.equalsIgnoreCase("NULL"))return null;
            if(c.type==Type.STRING) { if(s.length()<2||s.charAt(0)!='\''||s.charAt(s.length()-1)!='\'')throw new IllegalArgumentException("Ожидалась строка: "+s); return s.substring(1,s.length()-1).replace("''", "'"); }
            if(c.type==Type.INT)return Integer.valueOf(s); if(c.type==Type.DOUBLE)return Double.valueOf(s);
            if(c.type==Type.DATE)return LocalDate.parse(s.replace("'", ""));
            if(s.equalsIgnoreCase("TRUE"))return Boolean.TRUE; if(s.equalsIgnoreCase("FALSE"))return Boolean.FALSE;
            throw new IllegalArgumentException("Ожидалось TRUE или FALSE: "+s);
        }
        private static List<String> split(String text) {
            List<String> parts=new ArrayList<String>(); StringBuilder b=new StringBuilder(); boolean quoted=false;
            for(int i=0;i<text.length();i++){char ch=text.charAt(i); if(ch=='\''){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='\''){b.append("''");i++;continue;}quoted=!quoted;} if(ch==','&&!quoted){parts.add(b.toString().trim());b.setLength(0);}else b.append(ch);}
            if(quoted)throw syntax(); if(b.length()>0)parts.add(b.toString().trim()); return parts;
        }
        private static List<String> tuples(String text) {
            List<String> out=new ArrayList<String>(); int depth=0,start=-1; boolean quote=false;
            for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(ch=='\'')quote=!quote; if(!quote){if(ch=='('){if(depth++==0)start=i+1;}else if(ch==')'&&--depth==0)out.add(text.substring(start,i));}}
            if(depth!=0||out.isEmpty())throw syntax(); return out;
        }
        private static List<String> columnNames(Table t){List<String> n=new ArrayList<String>();for(Column c:t.columns)n.add(c.name);return n;}
        private static void print(List<String> cols,List<Map<String,Object>> rows){for(String c:cols)System.out.print("| "+c+" ");System.out.println("|");for(Map<String,Object> r:rows){for(String c:cols)System.out.print("| "+String.valueOf(r.get(c.trim().toLowerCase(Locale.ROOT)))+" ");System.out.println("|");}System.out.println(rows.size()+" строк.");}
        private static void validateKeys(Table t){Set<Object> keys=new HashSet<Object>();for(Map<String,Object> r:t.rows)if(r.get(t.primaryKey)==null||!keys.add(r.get(t.primaryKey)))throw new IllegalArgumentException("Нарушена уникальность первичного ключа");}
        private static IllegalArgumentException syntax(){return new IllegalArgumentException("Синтаксическая ошибка или неподдерживаемый формат SQL");}
    }
}
