package org.apache.hadoop.hive.ql.cube.metadata;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.ql.cube.metadata.Storage.LatestInfo;
import org.apache.hadoop.hive.ql.cube.metadata.Storage.LatestPartColumnInfo;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.thrift.TException;

/**
 * Wrapper class around Hive metastore to do cube metastore operations.
 *
 */
public class CubeMetastoreClient {
  private Hive metastore;
  private final HiveConf config;
  private boolean enableCaching;

  private CubeMetastoreClient(HiveConf conf) {
    this.config = conf;
  }

  // map from table name to Table
  private final Map<String, Table> allHiveTables = new HashMap<String, Table>();
  // map from cube name to Cube
  private final Map<String, Cube> allCubes = new HashMap<String, Cube>();
  // map from dim name to CubeDimensionTable
  private final Map<String, CubeDimensionTable> allDims = new HashMap<String, CubeDimensionTable>();
  // map from fact name to fact table
  private final Map<String, CubeFactTable> allFactTables = new HashMap<String, CubeFactTable>();

  private static CubeMetastoreClient instance;

  private SchemaGraph schemaGraph;
  /**
   * Get the instance of {@link CubeMetastoreClient} corresponding
   * to {@link HiveConf}
   *
   * @param conf
   * @return CubeMetastoreClient
   * @throws HiveException
   */
  public static CubeMetastoreClient getInstance(HiveConf conf)
      throws HiveException {
    if (instance == null) {
      instance = new CubeMetastoreClient(conf);
    }
    instance.metastore = Hive.get(conf);
    instance.enableCaching = conf.getBoolean(MetastoreConstants.METASTORE_ENABLE_CACHING, true);
    return instance;
  }

  private Hive getClient() {
    return metastore;
  }

  /**
   * Close the current metastore client
   */
  public static void close() {
    Hive.closeCurrent();
  }

  public void setCurrentDatabase(String currentDatabase) {
    SessionState.get().setCurrentDatabase(currentDatabase);
  }

  public String getCurrentDatabase() {
    return SessionState.get().getCurrentDatabase();
  }

  private void createStorageHiveTable(Table parent, Storage storage,
      StorageTableDesc crtTblDesc) throws HiveException {
    try {
      Table tbl = storage.getStorageTable(getClient(), parent, crtTblDesc);
      getClient().createTable(tbl);
    } catch (Exception e) {
      throw new HiveException("Exception creating table", e);
    }
  }

  private Table createCubeHiveTable(AbstractCubeTable table)
      throws HiveException {
    try {
      Table tbl = getClient().newTable(table.getName().toLowerCase());
      tbl.setTableType(TableType.MANAGED_TABLE);
      tbl.getTTable().getSd().setCols(table.getColumns());
      tbl.getTTable().getParameters().putAll(table.getProperties());
      getClient().createTable(tbl);
      return tbl;
    } catch (Exception e) {
      throw new HiveException("Exception creating table", e);
    }
  }

  /**
   * Create cube in metastore defined by {@link Cube} object
   *
   * @param cube the {@link Cube} object.
   * @throws HiveException
   */
  public void createCube(Cube cube) throws HiveException {
    createCubeHiveTable(cube);
  }

  /**
   * Create cube defined by measures and dimensions
   *
   * @param name Name of the cube
   * @param measures Measures of the cube
   * @param dimensions Dimensions of the cube
   *
   * @throws HiveException
   */
  public void createCube(String name, Set<CubeMeasure> measures,
      Set<CubeDimension> dimensions) throws HiveException {
    Cube cube = new Cube(name, measures, dimensions);
    createCube(cube);
  }

  /**
   * Create cube defined by measures, dimensions and properties
   *
   * @param name Name of the cube
   * @param measures Measures of the cube
   * @param dimensions Dimensions of the cube
   * @param properties Properties of the cube
   * @throws HiveException
   */
  public void createCube(String name, Set<CubeMeasure> measures,
      Set<CubeDimension> dimensions, Map<String, String> properties)
          throws HiveException {
    Cube cube = new Cube(name, measures, dimensions, properties);
    createCube(cube);
  }

  /**
   * Create a cube fact table
   *
   * @param cubeNames The cube names to which fact belongs to.
   * @param factName The fact name
   * @param columns The columns of fact table
   * @param storageAggregatePeriods Aggregate periods for the storages
   * @param weight Weight of the cube
   * @param properties Properties of fact table
   * @param storageTableDescs Map of storage to its storage table description
   *
   * @throws HiveException
   */
  public void createCubeFactTable(List<String> cubeNames, String factName,
      List<FieldSchema> columns,
      Map<String, Set<UpdatePeriod>> storageAggregatePeriods, double weight,
      Map<String, String> properties, Map<Storage, StorageTableDesc> storageTableDescs)
          throws HiveException {
    CubeFactTable factTable = new CubeFactTable(cubeNames, factName, columns,
        storageAggregatePeriods, weight, properties);
    createCubeTable(factTable, storageTableDescs);
  }

  /**
   * Create a cube dimension table
   *
   * @param dimName dimensions name
   * @param columns Columns of the dimension table
   * @param weight Weight of the dimension table
   * @param dimensionReferences References to other dimensions
   * @param storages Storages on which dimension is available without any dumps
   * @param properties Properties of dimension table
   * @param storageTableDescs Map of storage to its storage table description
   *
   * @throws HiveException
   */
  public void createCubeDimensionTable(String dimName,
      List<FieldSchema> columns, double weight,
      Map<String, List<TableReference>> dimensionReferences,
      Set<String> storageNames, Map<String, String> properties,
      Map<Storage, StorageTableDesc> storageTableDescs)
          throws HiveException {
    CubeDimensionTable dimTable = new CubeDimensionTable(dimName, columns,
        weight, storageNames, dimensionReferences, properties);
    createCubeTable(dimTable, storageTableDescs);
  }

  /**
   * Create a cube dimension table
   *
   * @param dimName dimensions name
   * @param columns Columns of the dimension table
   * @param weight Weight of the dimension table
   * @param dimensionReferences References to other dimensions
   * @param dumpPeriods Storage names and their dump periods on which dimension
   *  is available
   * @param properties properties of dimension table
   * @param storageTableDescs Map of storage to its storage table description
   * @throws HiveException
   */
  public void createCubeDimensionTable(String dimName,
      List<FieldSchema> columns, double weight,
      Map<String, List<TableReference>> dimensionReferences,
      Map<String, UpdatePeriod> dumpPeriods,
      Map<String, String> properties, Map<Storage,
      StorageTableDesc> storageTableDescs)
          throws HiveException {
    CubeDimensionTable dimTable = new CubeDimensionTable(dimName, columns,
        weight, dumpPeriods, dimensionReferences, properties);
    createCubeTable(dimTable, storageTableDescs);
  }

  /**
   * Create cube table defined and create all the corresponding storage tables
   *
   * @param cubeTable Can be fact or dimension table
   * @param storageTableDescs Map of storage to its storage table description
   * @throws HiveException
   */
  public void createCubeTable(AbstractCubeTable cubeTable,
      Map<Storage, StorageTableDesc> storageTableDescs)
          throws HiveException {
    // create virtual cube table in metastore
    Table cTable = createCubeHiveTable(cubeTable);

    if (storageTableDescs != null) {
      // create tables for each storage
      for (Map.Entry<Storage, StorageTableDesc> entry : storageTableDescs.entrySet()) {
        createStorageHiveTable(cTable, entry.getKey(), entry.getValue());
      }
    }
  }

  /**
   * Adds storage to fact and creates corresponding storage table
   *
   * @param fact The CubeFactTable
   * @param storage The storage
   * @param updatePeriods Update periods of the fact on the storage
   * @param storageTableDesc The storage table description
   *
   * @throws HiveException
   */
  public void addStorage(CubeFactTable fact, Storage storage,
      Set<UpdatePeriod> updatePeriods, StorageTableDesc storageTableDesc) throws HiveException {
    fact.addStorage(storage.getName(), updatePeriods);
    createStorageHiveTable(getTable(fact.getName()), storage, storageTableDesc);
    alterCubeTable(fact.getName(), getTable(fact.getName()), fact);
    updateFactCache(fact.getName());
  }

  /**
   * Adds storage to dimension and creates corresponding storage table
   *
   * @param dim The CubeDimensionTable
   * @param storage The storage
   * @param dumpPeriod The dumpPeriod if any, null otherwise
   * @param storageTableDesc The storage table description
   *
   * @throws HiveException
   */
  public void addStorage(CubeDimensionTable dim, Storage storage,
      UpdatePeriod dumpPeriod, StorageTableDesc storageTableDesc) throws HiveException {
    dim.alterSnapshotDumpPeriod(storage.getName(), dumpPeriod);
    createStorageHiveTable(getTable(dim.getName()), storage, storageTableDesc);
    alterCubeTable(dim.getName(), getTable(dim.getName()), dim);
    updateDimCache(dim.getName());
  }

  /**
   * Add a partition specified by the storage partition desc on the storage passed.
   *
   * @param partSpec The storage partition description
   * @param storage The storage object
   *
   * @throws HiveException
   */
  public void addPartition(StoragePartitionDesc partSpec, Storage storage)
          throws HiveException {
    String storageTableName = MetastoreUtil.getStorageTableName(
        partSpec.getCubeTableName(), storage.getPrefix());

    storage.addPartition(getClient(), partSpec,
        getLatestInfo(storageTableName, partSpec.getTimePartSpec(),
            partSpec.getUpdatePeriod()));
  }

  private LatestInfo getLatestInfo(String storageTableName,
      Map<String, Date> partitionTimestamps,
      UpdatePeriod updatePeriod) throws HiveException {
    Table hiveTable = getHiveTable(storageTableName);
    String timePartColsStr = hiveTable.getTTable().getParameters().get(
        MetastoreConstants.TIME_PART_COLUMNS);
    if (timePartColsStr != null) {
      LatestInfo latest = new LatestInfo();
      String[] timePartCols = StringUtils.split(timePartColsStr, ',');
      for (String partCol : timePartCols) {
        Date pTimestamp = partitionTimestamps.get(partCol);
        Partition part = getLatestPart(storageTableName, partCol);
        boolean makeLatest = true;
        if (part != null) {
          String latestTimeStampStr = part.getParameters().get(
              MetastoreUtil.getLatestPartTimestampKey(partCol));
          String latestPartUpdatePeriod = part.getParameters().get(
              MetastoreUtil.getLatestPartUpdatePeriodKey(partCol));
          UpdatePeriod latestUpdatePeriod = UpdatePeriod.valueOf(
              latestPartUpdatePeriod.toUpperCase());
          Date latestTimestamp = null;
          try {
            latestTimestamp = latestUpdatePeriod.format().parse(latestTimeStampStr);
          } catch (ParseException e) {
            throw new HiveException(e);
          }
          if (latestTimestamp.after(pTimestamp)) {
            makeLatest = false;
          }
        }

        if (makeLatest) {
          Map<String, String> latestParams = new HashMap<String, String>();
          latestParams.put(MetastoreUtil.getLatestPartTimestampKey(partCol),
              updatePeriod.format().format(pTimestamp));
          latestParams.put(MetastoreUtil.getLatestPartUpdatePeriodKey(partCol),
              updatePeriod.getName());
          latest.latestParts.put(partCol, new LatestPartColumnInfo(latestParams));
        }
      }
      return latest;
    } else {
      return null;
    }
  }

  private Map<String, String> getPartitionSpec(
      UpdatePeriod updatePeriod, Map<String, Date> partitionTimestamps) {
    Map<String, String> partSpec = new HashMap<String, String>();
    for (Map.Entry<String, Date> entry : partitionTimestamps.entrySet()) {
      String pval = updatePeriod.format().format(entry.getValue());
      partSpec.put(entry.getKey(), pval);
    }
    return partSpec;
  }

  boolean tableExists(String cubeName)
      throws HiveException {
    try {
      return (getClient().getTable(cubeName.toLowerCase(), false) != null);
    } catch (HiveException e) {
      throw new HiveException("Could not check whether table exists", e);
    }
  }

  boolean factPartitionExists(String factName,
      Storage storage, UpdatePeriod updatePeriod,
      Map<String, Date> partitionTimestamp, Map<String, String> partSpec)
          throws HiveException {
    String storageTableName = MetastoreUtil.getFactStorageTableName(
        factName, storage.getPrefix());
    return partitionExists(storageTableName, updatePeriod, partitionTimestamp,
        partSpec);
  }

  public boolean partitionExists(String storageTableName,
      UpdatePeriod updatePeriod,
      Map<String, Date> partitionTimestamps)
          throws HiveException {
    return partitionExists(storageTableName,
        getPartitionSpec(updatePeriod, partitionTimestamps));
  }

  public boolean partitionExistsByFilter(String storageTableName, String filter)
      throws HiveException {
    List<Partition> parts;
    try {
      parts = getClient().getPartitionsByFilter(
          getTable(storageTableName), filter);
    } catch (Exception e) {
      throw new HiveException("Could not find partitions for given filter", e);
    }
    return !(parts.isEmpty());
  }

  public List<Partition> getPartitionsByFilter(String storageTableName,
      String filter) throws HiveException {
    try {
    return getClient().getPartitionsByFilter(
        getTable(storageTableName), filter);
    } catch (Exception e) {
      throw new HiveException(e);
    }
  }

  public int getNumPartitionsByFilter(String storageTableName,
      String filter) throws MetaException, NoSuchObjectException,
      HiveException, TException {
    return getClient().getNumPartitionsByFilter(
        getTable(storageTableName), filter);
  }

  boolean partitionExists(String storageTableName, UpdatePeriod updatePeriod,
      Map<String, Date> partitionTimestamps, Map<String, String> partSpec)
          throws HiveException {
    partSpec.putAll(getPartitionSpec(updatePeriod, partitionTimestamps));
    return partitionExists(storageTableName, partSpec);
  }

  private boolean partitionExists(String storageTableName,
      Map<String, String> partSpec) throws HiveException {
    try {
      Table storageTbl = getTable(storageTableName);
      Partition p = getClient().getPartition(storageTbl, partSpec, false);
      return (p != null && p.getTPartition() != null);
    } catch (HiveException e) {
      throw new HiveException("Could not check whether table exists", e);
    }
  }

  boolean dimPartitionExists(String dimName,
      Storage storage, Map<String, Date> partitionTimestamps) throws HiveException {
    String storageTableName = MetastoreUtil.getDimStorageTableName(
        dimName, storage.getPrefix());
    return partitionExists(storageTableName,
        getDimensionTable(dimName).getSnapshotDumpPeriods().get(storage.getName()),
        partitionTimestamps);
  }

  boolean latestPartitionExists(String factName,
      Storage storage, String latestPartCol) throws HiveException {
    String storageTableName = MetastoreUtil.getFactStorageTableName(
        factName, storage.getPrefix());
    return partitionExistsByFilter(storageTableName,
        StorageConstants.getLatestPartFilter(latestPartCol));
  }

  Partition getLatestPart(String storageTableName,
      String latestPartCol) throws HiveException {
    List<Partition> latestParts = getPartitionsByFilter(storageTableName,
        StorageConstants.getLatestPartFilter(latestPartCol));
    if (latestParts != null && !latestParts.isEmpty()) {
      latestParts.get(0);
    }
    return null;
  }

  /**
   * Get the hive {@link Table} corresponding to the name
   *
   * @param tableName
   * @return {@link Table} object
   * @throws HiveException
   */
  public Table getHiveTable(String tableName) throws HiveException {
    return getTable(tableName);
  }

  private Table getTable(String tableName) throws HiveException {
    Table tbl;
    try {
      tbl = allHiveTables.get(tableName.toLowerCase());
      if (tbl == null) {
        tbl = getClient().getTable(tableName.toLowerCase());
        if (enableCaching) {
          allHiveTables.put(tableName.toLowerCase(), tbl);
        }
      }
    } catch (HiveException e) {
      throw new HiveException("Could not get table: " + tableName, e);
    }
    return tbl;
  }

  private Table refreshTable(String tableName) throws HiveException {
    Table tbl;
    try {
      tbl = getClient().getTable(tableName.toLowerCase());
      allHiveTables.put(tableName.toLowerCase(), tbl);
    } catch (HiveException e) {
      throw new HiveException("Could not get table: " + tableName, e);
    }
    return tbl;
  }

  public void dropHiveTable(String table) throws HiveException {
    metastore.dropTable(table);
    allHiveTables.remove(table.toLowerCase());
  }

  /**
   * Is the table name passed a fact table?
   *
   * @param tableName table name
   * @return true if it is cube fact, false otherwise
   * @throws HiveException
   */
  public boolean isFactTable(String tableName) throws HiveException {
    Table tbl = getTable(tableName);
    return isFactTable(tbl);
  }

  boolean isFactTable(Table tbl) {
    String tableType = tbl.getParameters().get(
        MetastoreConstants.TABLE_TYPE_KEY);
    return CubeTableType.FACT.name().equals(tableType);
  }

  boolean isFactTableForCube(Table tbl, String cube) {
    if (isFactTable(tbl)) {
      return CubeFactTable.getCubeNames(tbl.getTableName(),
          tbl.getParameters()).contains(cube.toLowerCase());
    }
    return false;
  }

  /**
   * Is the table name passed a dimension table?
   *
   * @param tableName table name
   * @return true if it is cube dimension, false otherwise
   * @throws HiveException
   */
  public boolean isDimensionTable(String tableName) throws HiveException {
    Table tbl = getTable(tableName);
    return isDimensionTable(tbl);
  }

  boolean isDimensionTable(Table tbl) throws HiveException {
    String tableType = tbl.getParameters().get(
        MetastoreConstants.TABLE_TYPE_KEY);
    return CubeTableType.DIMENSION.name().equals(tableType);
  }

  /**
   * Is the table name passed a cube?
   *
   * @param tableName table name
   * @return true if it is cube, false otherwise
   * @throws HiveException
   */
  public boolean isCube(String tableName) throws HiveException {
    Table tbl = getTable(tableName);
    return isCube(tbl);
  }

  /**
   * Is the hive table a cube table?
   *
   * @param tbl
   * @return
   * @throws HiveException
   */
  boolean isCube(Table tbl) throws HiveException {
    String tableType = tbl.getParameters().get(
        MetastoreConstants.TABLE_TYPE_KEY);
    return CubeTableType.CUBE.name().equals(tableType);
  }

  /**
   * Get {@link CubeFactTable} object corresponding to the name
   *
   * @param tableName The cube fact name
   * @return Returns CubeFactTable if table name passed is a fact table,
   *  null otherwise
   * @throws HiveException
   */

  public CubeFactTable getFactTable(String tableName) throws HiveException {
    Table tbl = getTable(tableName);
    return getFactTable(tbl);
  }

  private CubeFactTable getFactTable(Table tbl) throws HiveException {
    if (isFactTable(tbl)) {
      return new CubeFactTable(tbl);
    } else {
    }
    return null;
  }

  /**
   * Get {@link CubeDimensionTable} object corresponding to the name
   *
   * @param tableName The cube dimension name
   * @return Returns CubeDimensionTable if table name passed is a dimension
   *  table, null otherwise
   * @throws HiveException
   */
  public CubeDimensionTable getDimensionTable(String tableName)
      throws HiveException {
    CubeDimensionTable dimTable = allDims.get(tableName.toLowerCase());
    if (dimTable == null) {
      Table tbl = getTable(tableName);
      if (isDimensionTable(tbl)) {
        dimTable = getDimensionTable(tbl);
        if (enableCaching) {
          allDims.put(tableName.toLowerCase(), dimTable);
        }
      }
    }
    return dimTable;
  }

  private CubeDimensionTable getDimensionTable(Table tbl)
      throws HiveException {
    return new CubeDimensionTable(tbl);
  }

  /**
   * Get {@link Cube} object corresponding to the name
   *
   * @param tableName The cube name
   * @return Returns cube is table name passed is a cube, null otherwise
   * @throws HiveException
   */
  public Cube getCube(String tableName) throws HiveException {
    Cube cube = allCubes.get(tableName.toLowerCase());
    if (cube == null) {
      Table tbl = getTable(tableName);
      if (isCube(tbl)) {
        cube = getCube(tbl);
        if (enableCaching) {
          allCubes.put(tableName.toLowerCase(), cube);
        }
      }
    }
    return cube;
  }

  /**
   * Get {@link Cube} object corresponding to the name
   *
   * @param tableName The cube name
   * @return Returns cube is table name passed is a cube, null otherwise
   * @throws HiveException
   */
  public CubeFactTable getCubeFact(String tableName) throws HiveException {
    CubeFactTable fact = allFactTables.get(tableName.toLowerCase());
    if (fact == null) {
      Table tbl = getTable(tableName);
      fact = getFactTable(tbl);
      if (enableCaching) {
        allFactTables.put(tableName.toLowerCase(), fact);
      }
    }
    return fact;
  }

  private Cube getCube(Table tbl) {
    return new Cube(tbl);
  }

  /**
   * Get all dimension tables in metastore
   *
   * @return List of dimension tables
   *
   * @throws HiveException
   */
  public List<CubeDimensionTable> getAllDimensionTables()
      throws HiveException {

    List<CubeDimensionTable> dimTables = new ArrayList<CubeDimensionTable>();
    try {
      for (String table : getAllHiveTableNames()) {
        CubeDimensionTable dim = getDimensionTable(table);
        if (dim != null) {
          dimTables.add(dim);
        }
      }
    } catch (HiveException e) {
      throw new HiveException("Could not get all tables", e);
    }
    return dimTables;
  }

  /**
   * Get all cubes in metastore
   *
   * @return List of Cube objects
   * @throws HiveException
   */
  public List<Cube> getAllCubes()
      throws HiveException {
    List<Cube> cubes = new ArrayList<Cube>();
    try {
      for (String table : getAllHiveTableNames()) {
        Cube cube = getCube(table);
        if (cube != null) {
          cubes.add(cube);
        }
      }
    } catch (HiveException e) {
      throw new HiveException("Could not get all tables", e);
    }
    return cubes;
  }

  /**
   * Get all facts in metastore
   *
   * @return List of Cube Fact Table objects
   *
   * @throws HiveException
   */
  public List<CubeFactTable> getAllFacts()
      throws HiveException {
    List<CubeFactTable> facts = new ArrayList<CubeFactTable>();
    try {
      for (String table : getAllHiveTableNames()) {
        CubeFactTable fact = getCubeFact(table);
        if (fact != null) {
          facts.add(fact);
        }
      }
    } catch (HiveException e) {
      throw new HiveException("Could not get all tables", e);
    }
    return facts;
  }

  private List<String> getAllHiveTableNames() throws HiveException {
    return getClient().getAllTables();
  }

  /**
   * Get all fact tables of the cube.
   *
   * @param cube Cube object
   *
   * @return List of fact tables
   * @throws HiveException
   */
  public List<CubeFactTable> getAllFactTables(Cube cube) throws HiveException {
    List<CubeFactTable> cubeFacts = new ArrayList<CubeFactTable>();
    try {
      for (CubeFactTable fact : getAllFacts()) {
        if (fact.getCubeNames().contains(cube.getName().toLowerCase())) {
          cubeFacts.add(fact);
        }
      }
    } catch (HiveException e) {
      throw new HiveException("Could not get all tables", e);
    }
    return cubeFacts;
  }

  public List<String> getPartColNames(String tableName)
      throws HiveException {
    List<String> partColNames = new ArrayList<String>();
    Table tbl = getTable(tableName);
    for (FieldSchema f : tbl.getPartCols()) {
      partColNames.add(f.getName().toLowerCase());
    }
    return partColNames;
  }

  public boolean partColExists(String tableName, String partCol)
      throws HiveException {
    Table tbl = getTable(tableName);
    for (FieldSchema f : tbl.getPartCols()) {
      if (f.getName().equalsIgnoreCase(partCol)) {
        return true;
      }
    }
    return false;
  }

  public void setSchemaGraph(SchemaGraph schemaGraph) {
    this.schemaGraph = schemaGraph;
  }

  public SchemaGraph getSchemaGraph() {
    return schemaGraph;
  }

  /**
   * Returns true if columns changed
   *
   * @param table
   * @param hiveTable
   * @param cubeTable
   * @throws HiveException
   */
  private boolean alterCubeTable(String table, Table hiveTable,
      AbstractCubeTable cubeTable) throws HiveException {
    hiveTable.getParameters().putAll(cubeTable.getProperties());
    boolean columnsChanged = !(hiveTable.getCols().equals(
        cubeTable.getColumns()));
    if (columnsChanged) {
      hiveTable.getTTable().getSd().setCols(cubeTable.getColumns());
    }
    hiveTable.getTTable().getParameters().putAll(cubeTable.getProperties());
    try {
      metastore.alterTable(table, hiveTable);
    } catch (InvalidOperationException e) {
      throw new HiveException(e);
    }
    return columnsChanged;
  }

  private void alterHiveTable(String table, Table hiveTable,
      List<FieldSchema> columns) throws HiveException {
    hiveTable.getTTable().getSd().setCols(columns);
    try {
      metastore.alterTable(table, hiveTable);
    } catch (InvalidOperationException e) {
      throw new HiveException(e);
    }
    if (enableCaching) {
      // refresh the table in cache
      refreshTable(table);
    }
  }

  /**
   * Alter cube specified by the name to new definition
   *
   * @param cubeName The cube name to be altered
   * @param cube The new cube definition
   * @throws HiveException
   * @throws InvalidOperationException
   */
  public void alterCube(String cubeName, Cube cube)
      throws HiveException {
    Table cubeTbl = getTable(cubeName);
    if (isCube(cubeTbl)) {
      alterCubeTable(cubeName, cubeTbl, cube);
      if (enableCaching) {
        allCubes.put(cubeName, getCube(refreshTable(cubeName)));
      }
    } else {
      throw new HiveException(cubeName + " is not a cube");
    }
  }

  /**
   * Drop a cube with cascade flag
   *
   * @param cubeName
   * @param cascade If true, will drop all facts of the cube and
   *  their storage tables
   * @throws HiveException
   */
  public void dropCube(String cubeName) throws HiveException {
    if (isCube(cubeName)) {
      allCubes.remove(cubeName.toLowerCase());
      dropHiveTable(cubeName);
    } else {
      throw new HiveException(cubeName + " is not a cube");
    }
  }

  /**
   * Drop a fact with cascade flag
   *
   * @param factName
   * @param cascade If true, will drop all the storages of the fact
   * @throws HiveException
   */
  public void dropFact(String factName, boolean cascade) throws HiveException {
    if (isFactTable(factName)) {
      CubeFactTable fact = getFactTable(factName);
      if (cascade) {
        for (String storage : fact.getStorages()) {
          dropStorageFromFact(factName, storage);
        }
      }
      updateFactCache(factName);
      dropHiveTable(factName);
    } else {
      throw new HiveException(factName + " is not a CubeFactTable");
    }
  }

  /**
   * Drop a storage from fact
   *
   * @param factName
   * @param storage
   * @throws HiveException
   * @throws InvalidOperationException
   */
  public void dropStorageFromFact(String factName, String storage)
      throws HiveException {
    CubeFactTable cft = getFactTable(factName);
    cft.dropStorage(storage);
    dropHiveTable(MetastoreUtil.getFactStorageTableName(factName,
        Storage.getPrefix(storage)));
    alterCubeTable(factName, getTable(factName), cft);
    updateFactCache(factName);
  }

  /**
   * Drop a storage from dimension
   *
   * @param dimName
   * @param storage
   * @throws HiveException
   */
  public void dropStorageFromDim(String dimName, String storage)
      throws HiveException {
    getDimensionTable(dimName).dropStorage(storage);
    dropHiveTable(MetastoreUtil.getDimStorageTableName(dimName,
        Storage.getPrefix(storage)));
    alterCubeTable(dimName, getTable(dimName), getDimensionTable(dimName));
    updateDimCache(dimName);
  }

  /**
   * Drop the dimension
   *
   * @param dimName
   * @param cascade If true, will drop all the dimension storages
   *
   * @throws HiveException
   */
  public void dropDimension(String dimName, boolean cascade)
      throws HiveException {
    if (isDimensionTable(dimName)) {
      CubeDimensionTable dim = getDimensionTable(dimName);
      Set<String> dimStorages = new HashSet<String>();
      dimStorages.addAll(dim.getStorages());
      for(String storage : dimStorages) {
        dropStorageFromDim(dimName, storage);
      }
      dropHiveTable(dimName);
      allDims.remove(dimName.toLowerCase());
    } else {
      throw new HiveException(dimName + " is not a dimension table");
    }
  }

  /**
   * Alter a cubefact with new definition
   *
   * @param factTableName
   * @param cubeFactTable
   * @throws HiveException
   * @throws InvalidOperationException
   */
  public void alterCubeFactTable(String factTableName,
      CubeFactTable cubeFactTable) throws  HiveException {
    Table factTbl = getTable(factTableName);
    if (isFactTable(factTbl)) {
      boolean colsChanged = alterCubeTable(factTableName, factTbl,
          cubeFactTable);
      if (colsChanged) {
        // Change schema of all the storage tables
        for (String storage : cubeFactTable.getStorages()) {
          String storageTableName = MetastoreUtil.getFactStorageTableName(
              factTableName, Storage.getPrefix(storage));
          alterHiveTable(storageTableName, getTable(storageTableName),
              cubeFactTable.getColumns());
        }
      }
      updateFactCache(factTableName);
    } else {
      throw new HiveException(factTableName + " is not a fact table");
    }
  }

  private void updateFactCache(String factTableName) throws HiveException {
    if (enableCaching) {
      allFactTables.put(factTableName,
          getFactTable(refreshTable(factTableName)));
    }
  }

  private void updateDimCache(String dimName) throws HiveException {
    if (enableCaching) {
      allDims.put(dimName,
          getDimensionTable(refreshTable(dimName)));
    }
  }
  /**
   * Alter dimension table with new dimension definition
   *
   * @param dimTableName
   * @param cubeDimensionTable
   *
   * @throws HiveException
   * @throws InvalidOperationException
   */
  public void alterCubeDimensionTable(String dimTableName,
      CubeDimensionTable cubeDimensionTable) throws HiveException {
    Table dimTbl = getTable(dimTableName);
    if (isDimensionTable(dimTbl)) {
      boolean colsChanged = alterCubeTable(dimTableName, dimTbl,
          cubeDimensionTable);
      if (colsChanged) {
        // Change schema of all the storage tables
        for (String storage : cubeDimensionTable.getStorages()) {
          String storageTableName = MetastoreUtil.getDimStorageTableName(
              dimTableName, Storage.getPrefix(storage));
          alterHiveTable(storageTableName, getTable(storageTableName),
              cubeDimensionTable.getColumns());
        }
      }
      updateDimCache(dimTableName);
    } else {
      throw new HiveException(dimTableName + " is not a dimension table");
    }
  }
}
