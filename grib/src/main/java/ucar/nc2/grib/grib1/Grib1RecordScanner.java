/*
 * Copyright (c) 1998 - 2011. University Corporation for Atmospheric Research/Unidata
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

package ucar.nc2.grib.grib1;

import ucar.grib.GribNumbers;
import ucar.unidata.io.KMPMatch;
import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.util.StringUtil2;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Scan files and extract Grib1Records.
 *
 * @author John
 * @since 9/3/11
 */
public class Grib1RecordScanner {
  static private org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Grib1RecordScanner.class);
  static private final KMPMatch matcher = new KMPMatch("GRIB".getBytes());
  static private final boolean debug = false;
  static private final boolean debugGds = true;

  static public boolean isValidFile(RandomAccessFile raf) {
    try {
      raf.seek(0);
      boolean found = raf.searchForward(matcher, 16 * 1000); // look in first 16K
      if (!found) return false;
      raf.skipBytes(7); // will be positioned on byte 0 of indicator section
      int edition = raf.read(); // read at byte 8
      return (edition == 1);

    } catch (IOException e) {
     return false;
    }
  }

  ////////////////////////////////////////////////////////////

  private Map<Long, Grib1SectionGridDefinition> gdsMap = new HashMap<Long, Grib1SectionGridDefinition>();
  private ucar.unidata.io.RandomAccessFile raf = null;

  private byte[] header;
  private long startPos = 0;
  private long lastPos = 0;

  // deal with repeating sections - each becomes a Grib1Record
  private long repeatPos = -1;             // if > 0, we are in middle of repeating record
  private Grib1Record repeatRecord = null; // current repeating record

  public Grib1RecordScanner(RandomAccessFile raf) throws IOException {
    startPos = 0;
    this.raf = raf;
    raf.seek(startPos);
    raf.order(RandomAccessFile.BIG_ENDIAN);
    lastPos = startPos;
  }

  public boolean hasNext() throws IOException {
    if (lastPos >= raf.length()) return false;
    /* if (repeatPos > 0) {
      if (nextRepeating()) // this has created a new repeatRecord
        return true;
    } else {
      repeatRecord = null;
      // fall through to new record
    }  */

    raf.seek(lastPos);
    boolean more = raf.searchForward(matcher, -1); // will scan to end for another GRIB header
    if (more) {
      long foundAt = raf.getFilePointer();
      int sizeHeader = (int) (foundAt - lastPos);
      //if (sizeHeader > 30) sizeHeader = 30;
      header = new byte[sizeHeader];
      startPos = foundAt-sizeHeader;
      raf.seek(startPos);
      raf.read(header);
      if (debug) System.out.println(" 'GRIB' found at "+foundAt+" starting from lastPos "+ lastPos);
    }
    return more;
  }

  public Grib1Record next() throws IOException {
    if (repeatRecord != null) { // serve current repeatRecord if it exists
      return new Grib1Record(repeatRecord);
    }

    Grib1SectionIndicator is = null;
    try {
      is = new Grib1SectionIndicator(raf);
      Grib1SectionProductDefinition pds = new Grib1SectionProductDefinition(raf);
      Grib1SectionGridDefinition gds = pds.gdsExists() ? new Grib1SectionGridDefinition(raf) : new Grib1SectionGridDefinition(pds);
      if (!pds.gdsExists() && debugGds)
        System.out.printf(" NO GDS: center = %d, GridDefinition=%d file=%s%n", pds.getCenter(), pds.getGridDefinition(), raf.getLocation());

      Grib1SectionBitMap bitmap = pds.bmsExists() ? new Grib1SectionBitMap(raf) : null;
      Grib1SectionBinaryData dataSection = new Grib1SectionBinaryData(raf);
      if (dataSection.getStartingPosition() + dataSection.getLength() > is.getEndPos()) { // presumably corrupt
        raf.seek(dataSection.getStartingPosition()); // go back to start of the dataSection, in hopes of salvaging
        log.warn("BAD GRIB-2 data message at " + dataSection.getStartingPosition() + " header= " + StringUtil2.cleanup(header)+" for="+raf.getLocation());
        throw new IllegalStateException("Illegal Grib1SectionBinaryData Message Length");
      }

      /* from old code
          // obtain BMS or BDS offset in the file for this product
          if (pds.getPdsVars().getCenter() == 98) {  // check for ecmwf offset by 1 bug
            int length = GribNumbers.uint3(raf);  // should be length of BMS
            if ((length + raf.getFilePointer()) < EOR) {
              dataOffset = raf.getFilePointer() - 3;  // ok
            } else {
              //System.out.println("ECMWF off by 1 bug" );
              dataOffset = raf.getFilePointer() - 2;
            }
          } else {
            dataOffset = raf.getFilePointer();
          }
       */

      // look for duplicate gds
      long crc = gds.calcCRC(); // LOOK switch to hashCode ??
      Grib1SectionGridDefinition gdsCached = gdsMap.get(crc);
      if (gdsCached != null)
        gds = gdsCached;
      else
        gdsMap.put(crc, gds);

      long ending = is.getEndPos();

      // check that end section is correct
      boolean foundEnding = true;
      raf.seek(ending-4);
      for (int i = 0; i < 4; i++) {
        if (raf.read() != 55) {
          foundEnding = false;
          log.debug("Missing End of GRIB message at pos=" + ending + " header= " + StringUtil2.cleanup(header)+" for="+raf.getLocation());
          break;
        }
      }
      if (debug) System.out.printf(" read until %d grib ending at %d header ='%s' foundEnding=%s%n",
              raf.getFilePointer(), ending, StringUtil2.cleanup(header), foundEnding);
      lastPos = foundEnding ? raf.getFilePointer() : is.getEndPos();

      return new Grib1Record(header, is, gds, pds, bitmap, dataSection);

    } catch (Throwable t) {
      long pos = (is == null) ? -1 : is.getStartPos();
      log.warn("Bad Grib1 record in file {}, skipping pos={}", raf.getLocation(), pos);
      t.printStackTrace();
      lastPos = raf.getFilePointer();
      if (hasNext()) // skip forward
        return next();
    }

    return null; // last record was incomplete
  }

  /*
   * tricky bit of business. recapture the entire record based on drs position - for extracting a message
   * for validation.
   * @param raf             from this RandomAccessFile
   * @param drsPos          Grib1SectionDataRepresentation starts here
   */
  static public Grib1Record findRecordByDrspos(RandomAccessFile raf, long drsPos) throws IOException {
    Grib1Record result = null;
    Grib1RecordScanner scanner = new Grib1RecordScanner(raf);
    long pos = Math.max(0, drsPos-10000); // go back 10000 bytes
    raf.seek(pos);
    while (scanner.hasNext()) {  // find GRIB header
      result = scanner.next();
      if (result.getDataSection().getStartingPosition() == drsPos)
        return result;
      if (raf.getFilePointer() > drsPos)
        break;
    }
    return null;

  }

  public static void main(String[] args) throws IOException {
    int count = 0;
    RandomAccessFile raf = new RandomAccessFile("F:/data/cdmUnitTest/formats/grib1/RUC.wmo", "r");
    Grib1RecordScanner scan = new Grib1RecordScanner(raf);
    while (scan.hasNext()) {
      scan.next();
      count++;
    }
    raf.close();
    System.out.printf("count=%d%n",count);
  }
}
