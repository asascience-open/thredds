package ucar.nc2.stream;

import ucar.nc2.iosp.AbstractIOServiceProvider;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;
import ucar.nc2.util.CancelTask;
import ucar.unidata.io.RandomAccessFile;
import ucar.ma2.*;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Read nc stream file (raf), make into a NetcdfFile.
 */
public class NcStreamIosp extends AbstractIOServiceProvider {

  public boolean isValidFile(RandomAccessFile raf) throws IOException {
    raf.seek(0);
    return readAndTest(raf, NcStream.MAGIC_HEADER);
  }

  //////////////////////////////////////////////////////////////////////
  private RandomAccessFile raf;

  public void open(RandomAccessFile raf, NetcdfFile ncfile, CancelTask cancelTask) throws IOException {
    this.raf = raf;

    raf.seek(0);
    assert readAndTest(raf, NcStream.MAGIC_HEADER);

    int msize = readVInt(raf);
    System.out.println("READ header len= " + msize);

    byte[] m = new byte[msize];
    raf.read(m);
    NcStreamProto.Stream proto = NcStreamProto.Stream.parseFrom(m);

    NcStreamProto.Group root = proto.getRoot();

    for (NcStreamProto.Dimension dim : root.getDimsList()) {
      ncfile.addDimension(null, NcStream.makeDim(dim));
    }

    for (NcStreamProto.Attribute att : root.getAttsList()) {
      ncfile.addAttribute(null, NcStream.makeAtt(att));
    }

    for (NcStreamProto.Variable var : root.getVarsList()) {
      ncfile.addVariable(null, NcStream.makeVar(ncfile, null, null, var));
    }

    // LOOK why doesnt this work ?
    //CodedInputStream cis = CodedInputStream.newInstance(is);
    //cis.setSizeLimit(msize);
    //NcStreamProto.Stream proto = NcStreamProto.Stream.parseFrom(cis);

    while (!raf.isAtEndOfFile()) {
      assert readAndTest(raf, NcStream.MAGIC_DATA);

      int psize = readVInt(raf);
      System.out.println(" dproto len= " + psize);
      byte[] dp = new byte[psize];
      raf.read(dp);
      NcStreamProto.Data dproto = NcStreamProto.Data.parseFrom(dp);
      System.out.println(" dproto = " + dproto);

      int dsize = readVInt(raf);
      System.out.println(" data len= " + dsize);

      DataSection dataSection = new DataSection();
      dataSection.size = dsize;
      dataSection.filePos = raf.getFilePointer();
      dataSection.section = NcStream.makeSection( dproto.getSection());

      Variable v = ncfile.getRootGroup().findVariable( dproto.getVarName());
      v.setSPobject(dataSection);

      raf.skipBytes(dsize);
    }
  }

  private class DataSection {
    int size;
    long filePos;
    Section section;
  }

  public Array readData(Variable v, Section section) throws IOException, InvalidRangeException {
    DataSection dataSection = (DataSection) v.getSPobject();

    raf.seek(dataSection.filePos);
    byte[] data = new byte[ dataSection.size];
    raf.read(data);

    Array dataArray = Array.factory( v.getDataType(), v.getShape(), ByteBuffer.wrap( data));
    return dataArray.section( section.getRanges());
  }

  public void close() throws IOException {
    raf.close();
  }

  private int readVInt(RandomAccessFile raf) throws IOException {
    byte b = (byte) raf.read();
    int i = b & 0x7F;
    for (int shift = 7; (b & 0x80) != 0; shift += 7) {
      b = (byte) raf.read();
      i |= (b & 0x7F) << shift;
    }
    return i;
  }

  private boolean readAndTest(RandomAccessFile raf, byte[] test) throws IOException {
    byte[] b = new byte[test.length];
    raf.read(b);

    if (b.length != test.length) return false;
    for (int i = 0; i < b.length; i++)
      if (b[i] != test[i]) return false;
    return true;
  }

}