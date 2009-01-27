/*
 * Copyright 1998-2009 University Corporation for Atmospheric Research/Unidata
 *
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package ucar.nc2.ft.point.standard;

import ucar.nc2.*;
import ucar.nc2.ft.*;
import ucar.nc2.ft.point.StructureDataIteratorLimited;
import ucar.nc2.units.DateUnit;
import ucar.nc2.units.DateFormatter;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.ma2.*;

import java.util.*;
import java.io.IOException;
import java.text.ParseException;

/**
 * Implements "nested table" views of point feature datasets.
 * A NestedTable is initialized with a TableConfig.
 * <p/>
 * A nested table starts with a leaf table (no children), plus all of its parents.
 * There is a "join" for each child and parent.
 * <p/>
 * Assumes that we have Tables that can be iterated over with a StructureDataIterator.
 * A parent-child join assumes that for each row of the parent, a StructureDataIterator exists that
 * iterates over the rows of the child table for that parent.
 * <p/>
 * Nested Tables must be put in canonical form, based on feature type:
 * <ol>
 * <li> point : obsTable
 * <li> station : stnTable -> obsTable
 * <li> traj : trajTable -> obsTable
 * <li> profile : profileTable -> obsTable
 * <li> stationProfile : stnTable -> profileTable -> obsTable
 * <li> section : sectionTable -> trajTable -> obsTable
 * </ol>
 *
 * @author caron
 * @since Mar 28, 2008
 */
public class NestedTable {
  static private org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NestedTable.class);

  private NetcdfDataset ds;
  private Formatter errlog;
  private Table leaf, root;
  private FeatureType featureType;

  private CoordVarExtractor timeVE, nomTimeVE, latVE, lonVE, altVE;
  private CoordVarExtractor stnVE, stnDescVE, wmoVE, stnAltVE;

  private String featureVariableName = "featureName";
  private int nlevels;

  private DateFormatter dateFormatter = new DateFormatter();

  // A NestedTable Table is created after the Tables have been joined, and the leaves identified.
  NestedTable(NetcdfDataset ds, TableConfig config, Formatter errlog) {
    this.ds = ds;
    this.errlog = errlog;

    this.leaf = Table.factory(ds, config);
    this.root = getRoot();

    // use the featureType from the highest level table
    nlevels = 0;
    Table t = leaf;
    while (t != null) {
      if (t.getFeatureType() != null) featureType = t.getFeatureType();
      t = t.parent;
      if (!(t instanceof Table.TableTop)) // LOOK using nlevels is fishy
        nlevels++;
    }

    if (featureType == null) {
      featureType = FeatureDatasetFactoryManager.findFeatureType(ds);
    }

    // will find the first one, starting at the leaf and going up
    timeVE = findCoordinateAxis(Table.CoordName.Time, leaf, 0);
    latVE = findCoordinateAxis(Table.CoordName.Lat, leaf, 0);
    lonVE = findCoordinateAxis(Table.CoordName.Lon, leaf, 0);
    altVE = findCoordinateAxis(Table.CoordName.Elev, leaf, 0);
    nomTimeVE = findCoordinateAxis(Table.CoordName.TimeNominal, leaf, 0);

    // look for station info
    stnVE = findCoordinateAxis(Table.CoordName.StnId, leaf, 0);
    stnDescVE = findCoordinateAxis(Table.CoordName.StnDesc, leaf, 0);
    wmoVE = findCoordinateAxis(Table.CoordName.WmoId, leaf, 0);
    stnAltVE = findCoordinateAxis(Table.CoordName.StnAlt, leaf, 0);

    // LOOK: Major kludge
    if (featureType == null) {
      if (nlevels == 1) featureType = FeatureType.POINT;
      if (nlevels == 2) featureType = FeatureType.STATION;
      if (nlevels == 3) featureType = FeatureType.STATION_PROFILE;
    }

    // check for singleton
    if (((nlevels == 1) && (featureType == FeatureType.STATION) || (featureType == FeatureType.PROFILE) || (featureType == FeatureType.TRAJECTORY)) ||
            ((nlevels == 2) && (featureType == FeatureType.STATION_PROFILE) || (featureType == FeatureType.SECTION))) {

      // singleton. use file name as feature name, so aggregation will work
      StructureData sdata = StructureDataFactory.make(featureVariableName, ds.getLocation());
      TableConfig parentConfig = new TableConfig(Table.Type.Singleton, featureType.toString());
      parentConfig.sdata = sdata;
      Table newRoot = Table.factory(ds, parentConfig);

      //Join join = Join.factory(new TableConfig.JoinConfig(Join.Type.Identity));
      //join.joinTables(newRoot, root);

      root = newRoot;

      nlevels++;
    }
  }

  Table getRoot() {
    Table p = leaf;
    while (p.parent != null) p = p.parent;
    return p;
  }

  // look for a coord axis of the given type in the table and its parents
  private CoordVarExtractor findCoordinateAxis(Table.CoordName coordName, Table t, int nestingLevel) {
    if (t == null) return null;

    String axisName = t.findCoordinateVariableName(coordName);

    if (axisName != null) {
      Variable v = t.findVariable(axisName);
      if (v != null)
        return new CoordVarExtractorVariable(v, axisName, nestingLevel);

      // see if its in the StructureData
      if (t instanceof Table.TableSingleton) {
        Table.TableSingleton ts = (Table.TableSingleton) t;
        return new CoordVarStructureData(axisName, ts.sdata);
      }

      // see if its at the top level
      if (t instanceof Table.TableTop) {
        v = ds.findVariable(axisName);

        if (v != null)
          return new CoordVarTop(v);
        else
          return new CoordVarConstant(coordName.toString(), "", axisName); // assume its the actual value
      }

      errlog.format("NestedTable: cant find variable %s for coordinate type " + axisName, coordName);
    }

    // look in the parent
    return findCoordinateAxis(coordName, t.parent, nestingLevel + 1);
  }

  // knows how to get specific coordinate data from a table or its parents
  private class CoordVarExtractorVariable extends CoordVarExtractor {
    protected Variable coordVar;

    CoordVarExtractorVariable(Variable v, String axisName, int nestingLevel) {
      super(axisName, nestingLevel);
      this.coordVar = v;
    }

    public String getCoordValueString(StructureData sdata) {
      if (coordVar.getDataType().isString())
        return sdata.getScalarString(axisName);
      else if (coordVar.getDataType().isIntegral())
        return Integer.toString(sdata.convertScalarInt(axisName));
      else
        return Double.toString(sdata.convertScalarDouble(axisName));
    }

    public String getUnitsString() {
      return coordVar.getUnitsString();
    }

    public boolean isString() {
      return coordVar.getDataType().isString();
    }

    public double getCoordValue(StructureData sdata) {
      return sdata.convertScalarDouble(axisName);
    }
  }

  // knows how to get specific coordinate data from a table or its parents
  private class CoordVarTop extends CoordVarExtractor {
    protected Variable varTop;

    CoordVarTop(Variable v) {
      super(v.getName(), 0);
      this.varTop = v;
    }

    public double getCoordValue(StructureData sdata) {
      try {
        return varTop.readScalarDouble();
      } catch (IOException e) {
        throw new RuntimeException(e.getMessage());
      }
    }

    public String getUnitsString() {
      return varTop.getUnitsString();
    }

    public boolean isString() {
      return varTop.getDataType().isString();
    }

    public String getCoordValueString(StructureData sdata) {
      try {
        return varTop.readScalarString();
      } catch (IOException e) {
        throw new RuntimeException(e.getMessage());
      }
    }

  }

  // knows how to get specific coordinate data from a table or its parents
  private class CoordVarStructureData extends CoordVarExtractor {
    protected StructureData sdata;

    CoordVarStructureData(String axisName, StructureData sdata) {
      super(axisName, 0);
      this.sdata = sdata;
    }

    public double getCoordValue(StructureData ignore) {
      return sdata.convertScalarDouble(axisName);
    }

    public String getCoordValueString(StructureData ignore) {
      return sdata.getScalarString(axisName);
    }

    public String getUnitsString() {
      StructureMembers.Member m = sdata.findMember(axisName);
      return m.getUnitsString();
    }

    public boolean isString() {
      StructureMembers.Member m = sdata.findMember(axisName);
      return m.getDataType().isString();
    }

  }

  // a constant coordinate variable
  private class CoordVarConstant extends CoordVarExtractor {
    String units, value;

    CoordVarConstant(String name, String units, String value) {
      super(name, 0);
      this.units = units;
      this.value = value;
    }

    public double getCoordValue(StructureData sdata) {
      return Double.parseDouble(value);
    }

    public String getCoordValueString(StructureData sdata) {
      return value;
    }

    public String getUnitsString() {
      return units;
    }

    public boolean isString() {
      return true;
    }

    @Override
    public String toString() {
      return "CoordVarConstant value= " + value;
    }
  }

  public FeatureType getFeatureType() {
    return featureType;
  }

  public int getNumberOfLevels() {
    return nlevels;
  }

  public boolean hasCoords() {
    return (timeVE != null) && (latVE != null) && (lonVE != null);
  }

  public DateUnit getTimeUnit() {
    try {
      return new DateUnit(timeVE.getUnitsString());
    } catch (Exception e) {
      throw new IllegalArgumentException("Error on time string = " + timeVE.getUnitsString() + " == " + e.getMessage());
    }
  }

  public List<VariableSimpleIF> getDataVariables() {
    List<VariableSimpleIF> data = new ArrayList<VariableSimpleIF>();
    addDataVariables(data, leaf);
    return data;
  }

  // use recursion so that parent variables come first
  private void addDataVariables(List<VariableSimpleIF> list, Table t) {
    if (t.parent != null) addDataVariables(list, t.parent);
    list.addAll(t.cols);
  }

  public String getName() {
    Formatter formatter = new Formatter();
    formatter.format("%s", leaf.getName());

    Table t = leaf;
    while (t.parent != null) {
      t = t.parent;
      formatter.format("/%s", t.getName());
    }
    return formatter.toString();
  }

  public String toString() {
    Formatter formatter = new Formatter();
    formatter.format("NestedTable = %s\n", getName());
    formatter.format("  Time= %s\n", timeVE);
    formatter.format("  Lat= %s\n", latVE);
    formatter.format("  Lon= %s\n", lonVE);
    formatter.format("  Height= %s\n", altVE);

    return formatter.toString();
  }

  public void show(Formatter formatter) {
    formatter.format(" NestedTable = %s\n", getName());
    formatter.format("   nlevels = %d\n", nlevels);
    leaf.show(formatter, 2);
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////
  // not clear these methods should be here

  // Point

  public StructureDataIterator getObsDataIterator(int bufferSize) throws IOException {
    return root.getStructureDataIterator(null, bufferSize);
  }

  // Station or Station_Profile

  // allow Construct to override and make stations directly

  public List<Station> makeStations(int bufferSize) throws IOException {

    if (root instanceof Table.TableConstruct) {
      return constructStations((Table.TableConstruct) root);
    }

    // otherwise
    ArrayList<Station> result = new ArrayList<Station>();
    StructureDataIterator siter = getStationDataIterator(bufferSize);
    while (siter.hasNext()) {
      StructureData stationData = siter.next();
      result.add(makeStation(stationData));
    }
    return result;
  }

  // old way
  public StructureDataIterator getStationDataIterator(int bufferSize) throws IOException {
    Table stationTable = root;
    StructureDataIterator siter = stationTable.getStructureDataIterator(null, bufferSize);

    if (stationTable.limit != null) {
      Variable limitV = ds.findVariable(stationTable.limit);
      int limit = limitV.readScalarInt();
      return new StructureDataIteratorLimited(siter, limit);
    }

    return siter;
  }

  public StructureDataIterator getStationObsDataIterator(StructureData stationData, int bufferSize) throws IOException {
    return leaf.getStructureDataIterator(stationData, bufferSize);
  }

  // Trajectory, Profile
  public StructureDataIterator getFeatureDataIterator(int bufferSize) throws IOException {
    return root.getStructureDataIterator(null, bufferSize);
  }

  public StructureDataIterator getFeatureObsDataIterator(StructureData trajData, int bufferSize) throws IOException {
    return leaf.getStructureDataIterator(trajData, bufferSize);
  }

  // Station Profile
  public StructureDataIterator getStationProfileDataIterator(Cursor cursor, int bufferSize) throws IOException {
    if (cursor.what instanceof StationConstruct) {
      StationConstruct sc = (StationConstruct) cursor.what;
      return sc.getStructureDataIterator(bufferSize);
    }

    return root.getStructureDataIterator(cursor.tableData[2], bufferSize);
  }

  public StructureDataIterator getStationProfileObsDataIterator(Cursor cursor, int bufferSize) throws IOException {
    if (getFeatureType() != FeatureType.STATION_PROFILE)
      throw new UnsupportedOperationException("Not a StationProfileFeatureCollection");

    StructureData profileData = cursor.tableData[1];
    return leaf.getStructureDataIterator(profileData, bufferSize);
  }

  ///////////////////////////////////////////////////////////////////////////////

  public double getObsTime(Cursor cursor) {
    return getTime(timeVE, cursor.tableData);
  }

  public double getNomTime(Cursor cursor) {
    return getTime(nomTimeVE, cursor.tableData);
  }

  private double getTime(CoordVarExtractor cve, StructureData[] tableData) {
    if (cve == null) return Double.NaN;

    if (cve.isString()) {
      String timeString = timeVE.getCoordValueString(tableData);
      Date date;
      try {
        date = dateFormatter.isoDateTimeFormat(timeString);
      } catch (ParseException e) {
        log.error("Cant parse date - not ISO formatted, = " + timeString);
        return 0.0;
      }
      return date.getTime() / 1000.0; // LOOK

    } else {
      return cve.getCoordValue(tableData);
    }
  }

  public double getLatitude(Cursor cursor) {
    if (cursor.what instanceof StationConstruct)
      return ((StationConstruct) cursor.what).getLatitude();

    return latVE.getCoordValue(cursor.tableData);
  }

  public double getLongitude(Cursor cursor) {
    if (cursor.what instanceof StationConstruct)
      return ((StationConstruct) cursor.what).getLongitude();

    return lonVE.getCoordValue(cursor.tableData);
  }

  public EarthLocation getEarthLocation(Cursor cursor) {
    if (cursor.what instanceof StationConstruct)
      return ((StationConstruct) cursor.what);

    double lat = latVE.getCoordValue(cursor.tableData);
    double lon = lonVE.getCoordValue(cursor.tableData);
    double alt = (altVE == null) ? Double.NaN : altVE.getCoordValue(cursor.tableData);
    if (stnAltVE != null) {
      double stnElev = stnAltVE.getCoordValue(cursor.tableData);
      if (altVE == null)
        alt = stnElev;
      else
        alt += stnElev;
    }
    return new EarthLocationImpl(lat, lon, alt);
  }

  public String getFeatureName(StructureData sdata) {
    if (featureVariableName == null) return "unknown";
    StructureMembers.Member m = sdata.findMember(featureVariableName);
    if (m == null) return "unknown";
    return sdata.getScalarString(m);
  }

  public Station makeStation(StructureData stationData) {
    String stationName = stnVE.getCoordValueString(stationData);
    String stationDesc = (stnDescVE == null) ? "" : stnDescVE.getCoordValueString(stationData);
    String stnWmoId = (wmoVE == null) ? "" : wmoVE.getCoordValueString(stationData);

    double lat = latVE.getCoordValue(stationData);
    double lon = lonVE.getCoordValue(stationData);
    double elev = (stnAltVE == null) ? Double.NaN : stnAltVE.getCoordValue(stationData);
    //double elev = (altVE == null) ? Double.NaN : altVE.getCoordValue(stationData);

    return new StationImpl(stationName, stationDesc, stnWmoId, lat, lon, elev);
  }

  public StructureData makeObsStructureData(Cursor cursor) {
    return StructureDataFactory.make(cursor.tableData);
  }

  public void addParentJoin(Cursor cursor) throws IOException {
    if (leaf.extraJoin != null) {
      StructureData obsdata = cursor.tableData[0];
      StructureData extra = leaf.extraJoin.getJoinData(obsdata);
      cursor.tableData[0] = StructureDataFactory.make(obsdata, extra);
    }
  }

  /////////////////////////////////////////////////////////
  // stations get constructed by reading the obs and extracting

  private List<Station> constructStations(Table.TableConstruct stationTable) throws IOException {
    Map<String, StationConstruct> stnMap = new HashMap<String, StationConstruct>();
    ArrayList<Station> result = new ArrayList<Station>();
    StructureDataIterator iter = stationTable.getStructureDataIterator(null, -1);
    int recno = 0;
    while (iter.hasNext()) {
      StructureData sdata = iter.next();
      StationConstruct snew = makeStationConstruct(sdata);
      StationConstruct s = stnMap.get(snew.getName());
      if (s == null) {
        s = snew;
        s.struct = stationTable.struct;
        stnMap.put(s.getName(), s);
        result.add(s);
      }
      double obsTime = timeVE.getCoordValue(sdata);
      s.addIndex(recno, obsTime);
      recno++;
    }

    return result;
  }

  private StationConstruct makeStationConstruct(StructureData stationData) {
    String stationName = stnVE.getCoordValueString(stationData);
    String stationDesc = (stnDescVE == null) ? "" : stnDescVE.getCoordValueString(stationData);
    String stnWmoId = (wmoVE == null) ? "" : wmoVE.getCoordValueString(stationData);

    double lat = latVE.getCoordValue(stationData);
    double lon = lonVE.getCoordValue(stationData);
    double elev = (stnAltVE == null) ? Double.NaN : stnAltVE.getCoordValue(stationData);

    return new StationConstruct(stationName, stationDesc, stnWmoId, lat, lon, elev);
  }

  private class StationConstruct extends StationImpl {
    List<Index> index;
    Structure struct;

    StationConstruct(String name, String desc, String wmoId, double lat, double lon, double alt) {
      super(name, desc, wmoId, lat, lon, alt);
    }

    void addIndex(int recno, double time) {
      if (index == null)
        index = new ArrayList<Index>();

      Index i = new Index();
      i.recno = recno;
      i.time = time;
      index.add(i);
    }

    class Index {
      int recno;
      double time;
    }

    StructureDataIterator getStructureDataIterator(int bufferSize) {
      return new IndexedStructureDataIterator();
    }

    class IndexedStructureDataIterator implements ucar.ma2.StructureDataIterator {
      int count = 0;

      public boolean hasNext() throws IOException {
        return count < index.size();
      }

      public StructureData next() throws IOException {
        Index i = index.get(count++);
        try {
          return struct.readStructure(i.recno);
        } catch (InvalidRangeException e) {
          throw new IllegalStateException("bad recnum " + i.recno, e);
        }
      }

      public void setBufferSize(int bytes) {
      }

      public StructureDataIterator reset() {
        count = 0;
        return this;
      }
    }

  }

}