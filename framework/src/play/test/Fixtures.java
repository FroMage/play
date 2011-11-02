package play.test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.io.FileUtils;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.CustomClassLoaderConstructor;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.scanner.ScannerException;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses;
import play.data.binding.Binder;
import play.data.binding.types.DateBinder;
import play.db.DB;
import play.db.DBPlugin;
import play.db.Model;
import play.exceptions.DatabaseException;
import play.db.Model.Property;
import play.exceptions.UnexpectedException;
import play.exceptions.YAMLException;
import play.libs.IO;
import play.templates.TemplateLoader;
import play.vfs.VirtualFile;

public class Fixtures {

    static Pattern keyPattern = Pattern.compile("([^(]+)\\(([^)]+)\\)");
    static Map<String, Object> idCache = new HashMap<String, Object>();

    public static void clearIdCache(){
    	idCache.clear();
    }
    
    public static void executeSQL(String sqlScript) {
        for(String sql: sqlScript.split(";")) {
            if(sql.trim().length() > 0) {
                DB.execute(sql);
            }
        }
    }

    public static void executeSQL(File sqlScript) {
        executeSQL(IO.readContentAsString(sqlScript));
    }

    /**
     * Delete all Model instances for the given types using the underlying persistence mechanisms
     * @param types Types to delete
     */
    public static void delete(Class<? extends Model>... types) {
        idCache.clear();
        disableForeignKeyConstraints();
        for (Class<? extends Model> type : types) {
            try {
                Model.Manager.factoryFor(type).deleteAll();
            } catch(Exception e) {
                Logger.error(e, "While deleting " + type + " instances");
            }

        }
        enableForeignKeyConstraints();
        Play.pluginCollection.afterFixtureLoad();
    }

    /**
     * Delete all Model instances for the given types using the underlying persistence mechanisms
     * @param types Types to delete
     */
    public static void delete(List<Class<? extends Model>> classes) {
        @SuppressWarnings("unchecked")
        Class<? extends Model>[] types = new Class[classes.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = classes.get(i);
        }
        delete(types);
    }

    /**
     * Delete all Model instances for the all available types using the underlying persistence mechanisms
     */
    @SuppressWarnings("unchecked")
    public static void deleteAllModels() {
        List<Class<? extends Model>> classes = new ArrayList<Class<? extends Model>>();
        for (ApplicationClasses.ApplicationClass c : Play.classes.getAssignableClasses(Model.class)) {
            classes.add((Class<? extends Model>)c.javaClass);
        }
        Fixtures.delete(classes);
    }

    /**
     * Use deleteDatabase() instead
     * @deprecated use {@link deleteDatabase()} instead
     */
    @Deprecated
    public static void deleteAll() {
        deleteDatabase();
    }

    static String[] dontDeleteTheseTables = new String[] {"play_evolutions"};

    /**
     * Flush the entire JDBC database
     */
    public static void deleteDatabase() {
        try {
            idCache.clear();
            
            List<String> names = new ArrayList<String>();
            
            if (DBPlugin.url.startsWith("jdbc:solid:")) {
                // Retrieves the list of schemas available before retrieving the list of the corresponding tables when 
                // using Solid as database. This is a workaround to prevent the Solid driver (at least in its 2.0 
                // version) to also returns system tables in the case where no schema is specified (i.e. when it is null).
                ResultSet schemas = DB.getConnection().getMetaData().getSchemas();
                
                while (schemas.next()) {
                  String schema = schemas.getString("TABLE_SCHEM");
                  
                  addTables(names, schema);
                }
            } else {
                addTables(names, null);
            }
            
            disableForeignKeyConstraints();
            
            for (String name : names) {
                if(Arrays.binarySearch(dontDeleteTheseTables, name) < 0) {
                    if (Logger.isTraceEnabled()) {
                        Logger.trace("Dropping content of table %s", name);
                    }
                    
                    DB.execute(getDeleteTableStmt(name));
                }
            }
            enableForeignKeyConstraints();
            Play.pluginCollection.afterFixtureLoad();
        } catch (Exception e) {
            throw new RuntimeException("Cannot delete all table data : " + e.getMessage(), e);
        }
    }

    /**
     * @param name
     * @deprecated use {@link loadModels(String...)} instead
     */
    @Deprecated
    public static void load(String name) {
        loadModels(name);
    }

    /**
     * Load Model instances from a YAML file and persist them using the underlying persistence mechanism.
     * The format of the YAML file is constrained, see the Fixtures manual page
     * @param name Name of a YAML file somewhere in the classpath (or conf/)
     */
    public static void loadModels(String name) {
        VirtualFile yamlFile = null;
        for (VirtualFile vf : Play.javaPath) {
        	yamlFile = vf.child(name);
        	if (yamlFile != null && yamlFile.exists()) {
        		break;
        	}
        }
        if (yamlFile == null) {
        	throw new RuntimeException("Cannot load fixture " + name + ", the file was not found");
        }

        // Render yaml file with 
        String renderedYaml = TemplateLoader.load(yamlFile).render();
        loadModels(renderedYaml, yamlFile);
    }
    
    public static void loadModelsFromString(String yamlFixture) {
    	loadModels(yamlFixture, null);
    }
    
    public static void loadModels(String yamlFixture, VirtualFile yamlFile) {
        try {
            Yaml yaml = new Yaml();
            Object o = yaml.load(yamlFixture);
            if (o instanceof LinkedHashMap<?, ?>) {
                @SuppressWarnings("unchecked") LinkedHashMap<Object, Map<?, ?>> objects = (LinkedHashMap<Object, Map<?, ?>>) o;
                for (Object key : objects.keySet()) {
                    Matcher matcher = keyPattern.matcher(key.toString().trim());
                    if (matcher.matches()) {
                        String type = matcher.group(1);
                        String id = matcher.group(2);
                        if (!type.startsWith("models.")) {
                            type = "models." + type;
                        }
                        if (idCache.containsKey(type + "-" + id)) {
                            throw new RuntimeException("Cannot load fixture, duplicate id '" + id + "' for type " + type);
                        }
                        Map<String, String[]> params = new HashMap<String, String[]>();
                        if (objects.get(key) == null) {
                            objects.put(key, new HashMap<Object, Object>());
                        }
                        serialize(objects.get(key), "object", params);
                        @SuppressWarnings("unchecked")
                        Class<Model> cType = (Class<Model>)Play.classloader.loadClass(type);
                        resolveDependencies(cType, params, idCache);
                        Model model = (Model)Binder.bind("object", cType, cType, null, params);
                        for(Field f : model.getClass().getFields()) {
                            if (f.getType().isAssignableFrom(Map.class)) {	 	
                                f.set(model, objects.get(key).get(f.getName()));
                            }
                            if (f.getType().equals(byte[].class)) {
                                f.set(model, objects.get(key).get(f.getName()));
                            }
                        }
                        try{
                        	model._save();
                        }catch(Exception x){
                        	throw new UnexpectedException("Failed to load fixture: problem while saving object "+id, x);
                        }
                        Class<?> tType = cType;
                        // FIXME: this is most probably wrong since superclasses might share IDs implemented by disjoint
                        // subclasses. Besides why create the key for each if it's supposed to be the same value???
                        while (!tType.equals(Object.class)) {
                            idCache.put(tType.getName() + "-" + id, Model.Manager.factoryFor(cType).keyValue(model));
                            tType = tType.getSuperclass();
                        }
                    }
                }
            }
            // Most persistence engine will need to clear their state
            Play.pluginCollection.afterFixtureLoad();
        }catch (UnexpectedException x){
        	throw x;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class " + e.getMessage() + " was not found", e);
        } catch (ScannerException e) {
        	if(yamlFile != null)
        		throw new YAMLException(e, yamlFile);
        	throw new RuntimeException(e);
        } catch (Throwable e) {
            throw new RuntimeException("Cannot load fixture: " + e.getMessage(), e);
        }
    }

    /**
     * @deprecated use {@link loadModels(String...)} instead
     */
    @Deprecated
    public static void load(String... names) {
        for (String name : names) {
            loadModels(name);
        }
    }

    /**
     * @see loadModels(String name)
     */
    public static void loadModels(String... names) {
        for (String name : names) {
            loadModels(name);
        }
    }

    /**
     * @deprecated use {@link loadModels(String...)} instead
     */
    public static void load(List<String> names) {
        loadModels(names);
    }

    /**
     * @see loadModels(String name)
     */
    public static void loadModels(List<String> names) {
        String[] tNames = new String[names.size()];
        for (int i = 0; i < tNames.length; i++) {
            tNames[i] = names.get(i);
        }
        load(tNames);
    }

    /**
     * Load and parse a plain YAML file and returns the corresponding Java objects.
     * The YAML parser used is SnakeYAML (http://code.google.com/p/snakeyaml/)
     * @param name Name of a YAML file somewhere in the classpath (or conf/)me
     * @return Java objects
     */
    public static Object loadYaml(String name) {
        return loadYaml(name, Object.class);
    }

    /**
     * Load and parse a plain YAML file and returns the corresponding Java List.
     * The YAML parser used is SnakeYAML (http://code.google.com/p/snakeyaml/)
     * @param name Name of a YAML file somewhere in the classpath (or conf/)me
     * @return Java List representing the YAML data
     */
    public static List<?> loadYamlAsList(String name) {
        return (List<?>)loadYaml(name);
    }

    /**
     * Load and parse a plain YAML file and returns the corresponding Java Map.
     * The YAML parser used is SnakeYAML (http://code.google.com/p/snakeyaml/)
     * @param name Name of a YAML file somewhere in the classpath (or conf/)me
     * @return Java Map representing the YAML data
     */
    public static Map<?,?> loadYamlAsMap(String name) {
        return (Map<?,?>)loadYaml(name);
    }

    /**
     * Load and parse a plain YAML file and returns the corresponding Java Map.
     * The YAML parser used is SnakeYAML (http://code.google.com/p/snakeyaml/)
     * @param name Name of a YAML file somewhere in the classpath (or conf/)me
     * @param clazz the expected class
     * @return Object representing the YAML data
     */
    @SuppressWarnings("unchecked")
    public static <T> T loadYaml(String name, Class<T> clazz) {
        Yaml yaml = new Yaml(new CustomClassLoaderConstructor(clazz, Play.classloader));
        yaml.setBeanAccess(BeanAccess.FIELD);
        return (T)loadYaml(name, yaml);
    }

    @SuppressWarnings("unchecked")
    public static <T> T loadYaml(String name, Yaml yaml) {
        VirtualFile yamlFile = null;
        try {
            for (VirtualFile vf : Play.javaPath) {
                yamlFile = vf.child(name);
                if (yamlFile != null && yamlFile.exists()) {
                    break;
                }
            }
            InputStream is = Play.classloader.getResourceAsStream(name);
            if (is == null) {
                throw new RuntimeException("Cannot load fixture " + name + ", the file was not found");
            }
            Object o = yaml.load(is);
            return (T)o;
        } catch (ScannerException e) {
            throw new YAMLException(e, yamlFile);
        } catch (Throwable e) {
            throw new RuntimeException("Cannot load fixture " + name + ": " + e.getMessage(), e);
        }
    }


    /**
     * Delete a directory recursively
     * @param path relative path of the directory to delete
     */
    public static void deleteDirectory(String path) {
        try {
            FileUtils.deleteDirectory(Play.getFile(path));
        } catch (IOException ex) {
            throw new UnexpectedException(ex);
        }
    }

    // Private

    static void serialize(Map<?, ?> values, String prefix, Map<String, String[]> serialized) {
        for (Object key : values.keySet()) {
            Object value = values.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?>) {
                serialize((Map<?, ?>) value, prefix + "." + key, serialized);
            } else if (value instanceof Date) {
                serialized.put(prefix + "." + key.toString(), new String[]{new SimpleDateFormat(DateBinder.ISO8601).format(((Date) value))});
            } else if (value instanceof List<?>) {
                List<?> l = (List<?>) value;
                String[] r = new String[l.size()];
                int i = 0;
                for (Object el : l) {
                    r[i++] = el.toString();
                }
                serialized.put(prefix + "." + key.toString(), r);
            } else if (value instanceof String && value.toString().matches("<<<\\s*\\{[^}]+}\\s*")) {
                Matcher m = Pattern.compile("<<<\\s*\\{([^}]+)}\\s*").matcher(value.toString());
                m.find();
                String file = m.group(1);
                VirtualFile f = Play.getVirtualFile(file);
                if (f != null && f.exists()) {
                    serialized.put(prefix + "." + key.toString(), new String[]{f.contentAsString()});
                }
            } else {
                serialized.put(prefix + "." + key.toString(), new String[]{value.toString()});
            }
        }
    }

    protected static void resolveDependencies(Class<? extends Model> type, Map<String, String[]> serialized, Map<String, Object> idCache) throws Exception {
        for (Model.Property field : Model.Manager.factoryFor(type).listProperties()) {
            if (field.isRelation) {
            	String prefix = "object." + field.name;
                String[] ids = serialized.get(prefix);
                Object[] persistedIds = null;
                if (ids != null) {
                	persistedIds = new Object[ids.length];
                    for (int i = 0; i < ids.length; i++) {
                        String id = ids[i];
                        id = field.relationType.getName() + "-" + id;
                        if (!idCache.containsKey(id)) {
                            throw new RuntimeException("No previous reference found for object of type " + field.name + " with key " + ids[i]);
                        }
                        persistedIds[i] = idCache.get(id);
                    }
                }
                serialized.remove(prefix);
                if(persistedIds != null)
                	serializeKey(prefix, field.relationType, persistedIds, serialized, idCache);
             }
        }
    }
    
    /**
     * Retrieves the names of the tables available in the specified schema and adds them to the given list. 
     * 
     * @param names list of table names to update
     * @param schema optional schema name
     * @throws SQLException in case of error
     */
    private static void addTables(List<String> names, String schema) throws SQLException {
      ResultSet tables = DB.getConnection().getMetaData().getTables(null, schema, null, new String[] { "TABLE" });
      
      while (tables.next()) {
        String name = tables.getString("TABLE_NAME");
        
        names.add(name);
      }
    }

    private static void serializeKey(String prefix,
    		Class<?> relationType, Object[] persistedIds, Map<String, String[]> serialized, Map<String, Object> idCache) throws Exception {
        @SuppressWarnings("unchecked")
		List<Model.Property> keys = Model.Manager.factoryFor((Class<? extends Model>) relationType).listKeys();
        // serialise each ID into as many keys
        for(Property key : keys){
        	String fieldName = prefix + "." + key.name; 
        	if(key.isRelation){
        		// get that part of the key
        		Object[] idParts = new Object[persistedIds.length];
        		for (int i = 0; i < idParts.length; i++) {
        			idParts[i] = PropertyUtils.getSimpleProperty(persistedIds[i], key.name);
        		}
        		serializeKey(fieldName, key.relationType, idParts, serialized, idCache);
        	}else{
        		// not composite so it must be serialisable as string
        		String[] ids= new String[persistedIds.length];
        		for (int i = 0; i < ids.length; i++) {
        			ids[i] = persistedIds[i].toString();
        		}
        		serialized.put(fieldName, ids);
        	}
        }
	}

    private static void disableForeignKeyConstraints() {
        if (DBPlugin.url.startsWith("jdbc:oracle:")) {
            DB.execute("begin\n"
                    + "for i in (select constraint_name, table_name from user_constraints where constraint_type ='R'\n"
                    + "and status = 'ENABLED') LOOP\n"
                    + "execute immediate 'alter table '||i.table_name||' disable constraint '||i.constraint_name||'';\n"
                    + "end loop;\n"
                    + "end;"
            );
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:hsqldb:")) {
            DB.execute("SET REFERENTIAL_INTEGRITY FALSE");
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:h2:")) {
            DB.execute("SET REFERENTIAL_INTEGRITY FALSE");
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:mysql:")) {
            DB.execute("SET foreign_key_checks = 0;");
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:postgresql:")) {
            DB.execute("SET CONSTRAINTS ALL DEFERRED");
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:sqlserver:")) {
            Statement exec=null;

            try {
                List<String> names = new ArrayList<String>();
                Connection connection = DB.getConnection();

                ResultSet rs = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"});
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    names.add(name);
                }

                    // Then we disable all foreign keys
                exec = connection.createStatement();
                for (String tableName:names)
                    exec.addBatch("ALTER TABLE " + tableName+" NOCHECK CONSTRAINT ALL");
                exec.executeBatch();
                exec.close();
                
                return;
            } catch (SQLException ex) {
                throw new DatabaseException("Error while disabling foreign keys", ex);
            }
        }

        // Maybe Log a WARN for unsupported DB ?
        Logger.warn("Fixtures : unable to disable constraints, unsupported database : " + DBPlugin.url);
    }

    private static void enableForeignKeyConstraints() {
        if (DBPlugin.url.startsWith("jdbc:oracle:")) {
            DB.execute("begin\n"
                    + "for i in (select constraint_name, table_name from user_constraints where constraint_type ='R'\n"
                    + "and status = 'DISABLED') LOOP\n"
                    + "execute immediate 'alter table '||i.table_name||' enable constraint '||i.constraint_name||'';\n"
                    + "end loop;\n"
                    + "end;"
            );
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:hsqldb:")) {
            DB.execute("SET REFERENTIAL_INTEGRITY TRUE");
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:h2:")) {
            DB.execute("SET REFERENTIAL_INTEGRITY TRUE");
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:mysql:")) {
            DB.execute("SET foreign_key_checks = 1;");
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:postgresql:")) {
            return;
        }

        if (DBPlugin.url.startsWith("jdbc:sqlserver:")) {
           Connection connect = null;
            Statement exec=null;
            try {
                connect = DB.getConnection();
                // We must first drop all foreign keys
                ArrayList<String> checkFKCommands=new ArrayList<String>();
                exec=connect.createStatement();
                ResultSet rs=exec.executeQuery("SELECT 'ALTER TABLE ' + TABLE_SCHEMA + '.[' + TABLE_NAME +'] WITH CHECK CHECK CONSTRAINT [' + CONSTRAINT_NAME + ']' FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_TYPE = 'FOREIGN KEY'");
                while (rs.next())
                {
                    checkFKCommands.add(rs.getString(1));
                }
                exec.close();
                exec=null;

                 // Now we have the drop commands, let's execute them
                exec=connect.createStatement();
                for (String sql:checkFKCommands)
                    exec.addBatch(sql);
                exec.executeBatch();
                exec.close();
            } catch (SQLException ex) {
                throw new DatabaseException("Cannot enable foreign keys", ex);
            }
            return;
          }

        Logger.warn("Fixtures : unable to enable constraints, unsupported database : " + DBPlugin.url);
    }
    
    /**
     * Retrieves the SQL statement to delete the specified table.
     * 
     * @param name name of the table to delete
     * @return the corresponding SQL statement
     */
    static String getDeleteTableStmt(String name) {
        if (DBPlugin.url.startsWith("jdbc:mysql:") || DBPlugin.url.startsWith("jdbc:oracle:")) {
            return "TRUNCATE TABLE " + name + ';';
        } else if (DBPlugin.url.startsWith("jdbc:postgresql:")) {
            return "TRUNCATE TABLE " + name + " cascade" + ';';
        } else if (DBPlugin.url.startsWith("jdbc:solid:")) {
            // Returns a SQL statement without any semicolon appended as otherwise Solid will throw an 'Illegal DOUBLE  
            // PREC constant ;' parse error
            return "DELETE FROM " + name;
        } else {
            return "DELETE FROM " + name + ';';
        }
    }
}