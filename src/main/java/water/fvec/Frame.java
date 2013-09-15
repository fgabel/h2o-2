package water.fvec;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import water.*;
import water.fvec.Vec.VectorGroup;

/**
 * A collection of named Vecs.  Essentially an R-like data-frame.  Multiple
 * Frames can reference the same Vecs.  A Frame is a lightweight object, it is
 * meant to be cheaply created and discarded for data munging purposes.
 * E.g. to exclude a Vec from a computation on a Frame, create a new Frame that
 * references all the Vecs but this one.
 */
public class Frame extends Iced {
  public String[] _names;
  public Vec[] _vecs;
  private Vec _col0;  // First readable vec; fast access to the VecGroup's Chunk layout

  public Frame( String[] names, Vec[] vecs ) { _names=names; _vecs=vecs; }
  public Frame( Frame fr ) { _names=fr._names.clone(); _vecs=fr._vecs.clone(); _col0 = fr._col0; }

  /** Finds the first column with a matching name.  */
  public int find( String name ) {
    for( int i=0; i<_names.length; i++ )
      if( name.equals(_names[i]) )
        return i;
    return -1;
  }

 /** Appends a named column, keeping the last Vec as the response */
  public void add( String name, Vec vec ) {
    // TODO : needs a compatibility-check!!!
    final int len = _names.length;
    _names = Arrays.copyOf(_names,len+1);
    _vecs  = Arrays.copyOf(_vecs ,len+1);
    _names[len] = name;
    _vecs [len] = vec ;
  }

  /** Removes the first column with a matching name.  */
  public Vec remove( String name ) { return remove(find(name)); }

  /** Removes a numbered column. */
  public Vec [] remove( int [] idxs ) {
    for(int i :idxs)if(i < 0 || i > _vecs.length)
      throw new ArrayIndexOutOfBoundsException();
    Arrays.sort(idxs);
    Vec [] res = new Vec[idxs.length];
    Vec [] rem = new Vec[_vecs.length-idxs.length];
    String [] names = new String[rem.length];
    int j = 0;
    int k = 0;
    int l = 0;
    for(int i = 0; i < _vecs.length; ++i)
      if(j < idxs.length && i == idxs[j]){
        ++j;
        res[k++] = _vecs[i];
      } else {
        rem[l] = _vecs[i];
        names[l] = _names[i];
        ++l;
      }
    _vecs = rem;
    _names = names;
    assert l == rem.length && k == idxs.length;
    return res;
  }
  /** Removes a numbered column. */
  public Vec remove( int idx ) {
    int len = _names.length;
    if( idx < 0 || idx >= len ) return null;
    Vec v = _vecs[idx];
    System.arraycopy(_names,idx+1,_names,idx,len-idx-1);
    System.arraycopy(_vecs ,idx+1,_vecs ,idx,len-idx-1);
    _names = Arrays.copyOf(_names,len-1);
    _vecs  = Arrays.copyOf(_vecs ,len-1);
    if( v == _col0 ) _col0 = null;
    return v;
  }

  public final Vec[] vecs() { return _vecs; }
  public final String[] names() { return _names; }
  public int  numCols() { return _vecs.length; }
  public long numRows(){ return anyVec().length();}

  /** All the domains for enum columns; null for non-enum columns.  */
  public String[][] domains() {
    String ds[][] = new String[_vecs.length][];
    for( int i=0; i<_vecs.length; i++ )
      ds[i] = _vecs[i].domain();
    return ds;
  }

  /** Returns the first readable vector. */
  public Vec anyVec() {
    if( _col0 != null ) return _col0;
    for( Vec v : _vecs )
      if( v != null && v.readable() )
        return (_col0 = v);
    return null;
  }

  /** Check that the vectors are all compatible.  All Vecs have their content
   *  sharded using same number of rows per chunk.  */
  public void checkCompatible( ) {
    Vec v0 = anyVec();
    int nchunks = v0.nChunks();
    for( Vec vec : _vecs ) {
      if( vec instanceof AppendableVec ) continue; // New Vectors are endlessly compatible
      if( vec.nChunks() != nchunks )
        throw new IllegalArgumentException("Vectors different numbers of chunks, "+nchunks+" and "+vec.nChunks());
    }
    // Also check each chunk has same rows
    for( int i=0; i<nchunks; i++ ) {
      long es = v0.chunk2StartElem(i);
      for( Vec vec : _vecs )
        if( !(vec instanceof AppendableVec) && vec.chunk2StartElem(i) != es )
          throw new IllegalArgumentException("Vector chunks different numbers of rows, "+es+" and "+vec.chunk2StartElem(i));
    }
  }

  public void closeAppendables() {closeAppendables(new Futures());}
  // Close all AppendableVec
  public void closeAppendables(Futures fs) {
    _col0 = null;               // Reset cache
    for( int i=0; i<_vecs.length; i++ ) {
      Vec v = _vecs[i];
      if( v != null && v instanceof AppendableVec )
        _vecs[i] = ((AppendableVec)v).close(fs);
    }
  }

  // True if any Appendables exist
  public boolean hasAppendables() {
    for( Vec v : _vecs )
      if( v instanceof AppendableVec )
        return true;
    return false;
  }

  public void remove() {
    remove(new Futures());
  }

  /** Actually remove/delete all Vecs from memory, not just from the Frame. */
  public void remove(Futures fs){
    if(_vecs.length > 0){
      VectorGroup vg = _vecs[0].group();
      for( Vec v : _vecs )
        UKV.remove(v._key,fs);
      DKV.remove(vg._key);
    }
    _names = new String[0];
    _vecs = new Vec[0];
  }

  public long byteSize() {
    long sum=0;
    for( int i=0; i<_vecs.length; i++ )
      sum += _vecs[i].byteSize();
    return sum;
  }

  @Override public String toString() {
    // Across
    String s="{"+_names[0];
    long bs=_vecs[0].byteSize();
    for( int i=1; i<_names.length; i++ ) {
      s += ","+_names[i];
      bs+= _vecs[i].byteSize();
    }
    s += "}, "+PrettyPrint.bytes(bs)+"\n";
    // Down
    Vec v0 = anyVec();
    if( v0 == null ) return s;
    int nc = v0.nChunks();
    s += "Chunk starts: {";
    for( int i=0; i<nc; i++ ) s += v0.elem2BV(i)._start+",";
    s += "}";
    return s;
  }

  private String toStr( long idx, int col ) {
    return _names[col]+"="+(_vecs[col].isNA(idx) ? "NA" : _vecs[col].at(idx));
  }
  public String toString( long idx ) {
    String s="{"+toStr(idx,0);
    for( int i=1; i<_names.length; i++ )
       s += ","+toStr(idx,i);
    return s+"}";
  }


  /**
   * splits frame into desired fractions via a uniform random draw.  <b> DOES NOT </b> promise such a division, and for small numbers of rows,
   * you get what you get
   *
   * @param fractions.  must sum to 1.0.  eg {0.8, 0.2} to get an 80/20 train/test split
   *
   * @return array of frames
   */
  public Frame[] splitFrame(double[] fractions){

    double[] splits = new double[ fractions.length ];
    double cumsum = 0.;
    for( int i=0; i < fractions.length; i++ ){
      cumsum += fractions[ i ];
      splits[ i ] = cumsum;
    }

    splits[ splits.length - 1 ] = 1.01; // force row to be assigned somewhere, even if the fractions passed in are garbage

    DataSplitter task = new DataSplitter();
    task._fr = this;
    task._splits = splits;
    task.init();

    task.doAll( this );

    Frame[] frames = task.finish();
    return frames;
  }

  /**
   * split a frame into multiple frames, with the data split as desired <br>
   * NB: this allocates fvecs; caller is responsible for remove-ing them <br>
   *<br>
   * TODO: pregenerate random numbers and make deterministic <br>
   * TODO: allow perfect splitting instead of at-random, particularly for unit tests
   */
  public static class DataSplitter extends MRTask2<DataSplitter> {
    AppendableVec[][] _vecs;
    double[] _splits;


    /**
     * must be called before doAll to allocate vecs
     */
    public void init(){
      AppendableVec[][] vecs = new AppendableVec[ _splits.length ][];
      for( int split=0; split < vecs.length; split++ ) {
        vecs[ split ] = new AppendableVec[ _fr._vecs.length ];
        for( int column=0; column < vecs[ split ].length; column++ )
          vecs[ split ][ column ] = new AppendableVec( UUID.randomUUID().toString() );
      }

      _vecs = vecs;
    }

    /**
     * return the new frames
     */
    public Frame[] finish(){
      Frame[] frames = new Frame[ _splits.length ];

      for( int split=0; split < _vecs.length; split++ ){
        Vec[] vecs = new Vec[ _fr._vecs.length ];

        for( int column=0; column < _vecs[ split ].length; column++ ) {
          vecs[ column ] = _vecs[ split ][ column ].close( null );
          if( _fr._vecs[ column ].isEnum() )
            vecs[ column ]._domain = _fr._vecs[ column ]._domain.clone();

        }
        frames[ split ] = new Frame( _fr.names(), vecs );

      }
      return frames;
    }



    @Override public void map(Chunk[] cs) {
      Random random = new Random();

      NewChunk[][] new_chunks = new NewChunk[ _vecs.length ][];
      for( int split=0; split < _vecs.length; split++ ){
        new_chunks[ split ] = new NewChunk[ _vecs[ split ].length ];
        for( int column=0; column < new_chunks[ split ].length; column++ )
          new_chunks[ split ][ column ] = new NewChunk( _vecs[ split ][ column ], cs[ column ].cidx() );
      }

      for( int chunk_row = 0; chunk_row < cs[ 0 ]._len; chunk_row++ ){
        double draw = random.nextDouble();
        int split = 0;
        while( draw > _splits[ split ]){ split++; }

        for( int column = 0; column < cs.length; column++ ){
          if( _fr._vecs[ column ].isEnum() ){
            if( !cs[ column ].isNA0( chunk_row ) )
              new_chunks[ split ][ column ].addEnum( (int) cs[ column ].at80( chunk_row ) );
            else
              new_chunks[ split ][ column ].addNA();

          } else if( _fr._vecs[ column ].isInt() ){
            if( !cs[ column ].isNA0( chunk_row ) )
              new_chunks[ split ][ column ].addNum( cs[ column ].at80( chunk_row ), 0 );
            else
              new_chunks[ split ][ column ].addNA();

          } else { // assume double; NaN == NA so should be able to just assign
              new_chunks[ split ][ column ].addNum( cs[ column ].at0( chunk_row ) );
          }
        }
      }

      for( int split=0; split < new_chunks.length; split++ )
        for( int column=0; column < new_chunks[ split ].length; column++ )
          new_chunks[ split ][ column ].close(cs[ column ].cidx(), null);
    }
  }

  public InputStream toCSV(boolean headers) {
    return new CSVStream(headers);
  }

  private class CSVStream extends InputStream {
    byte[] _line;
    int _position;
    long _row;

    CSVStream(boolean headers) {
      StringBuilder sb = new StringBuilder();
      if( headers ) {
        sb.append('"' + _names[0] + '"');
        for(int i = 1; i < _vecs.length; i++)
          sb.append(',').append('"' + _names[i] + '"');
        sb.append('\n');
      }
      _line = sb.toString().getBytes();
    }

    @Override public int available() throws IOException {
      if(_position == _line.length) {
        if(_row == numRows())
          return 0;
        StringBuilder sb = new StringBuilder();
        for( int i = 0; i < _vecs.length; i++ ) {
          if(i > 0) sb.append(',');
          if(!_vecs[i].isNA(_row)) {
            if(_vecs[i].isEnum()) sb.append('"' + _vecs[i]._domain[(int) _vecs[i].at8(_row)] + '"');
            else if(_vecs[i].isInt()) sb.append(_vecs[i].at8(_row));
            else sb.append(_vecs[i].at(_row));
          }
        }
        sb.append('\n');
        _line = sb.toString().getBytes();
        _position = 0;
        _row++;
      }
      return _line.length - _position;
    }

    @Override public void close() throws IOException {
      super.close();
      _line = null;
    }

    @Override public int read() throws IOException {
      return available() == 0 ? -1 : _line[_position++];
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
      int n = available();
      if(n > 0) {
        n = Math.min(n, len);
        System.arraycopy(_line, _position, b, off, n);
        _position += n;
      }
      return n;
    }
  }
}
