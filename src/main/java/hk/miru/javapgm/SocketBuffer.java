/* Primitive data buffer for PGM packets.
 */
package hk.miru.javapgm;

import static hk.miru.javapgm.Preconditions.checkArgument;
import static hk.miru.javapgm.Preconditions.checkNotNull;

import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;

@SuppressWarnings("PointlessBitwiseExpression")
public class SocketBuffer {

	private hk.miru.javapgm.Socket	_socket = null;
	private long			_timestamp = 0;
	private TransportSessionId	_tsi = null;

	private SequenceNumber		_sequence = null;

	private ControlBuffer		_cb = null;

	private int			_len = 0;	/* actual data */

	private Header			_header = null;
	private OriginalData		_odata = null;
	private OptionFragment		_opt_fragment = null;

	private byte[]			_buf = null;

	private int			_head = 0;
	private int			_data = 0;
	private int			_tail = 0;
	private int			_end = 0;        
        private AtomicInteger           _users = new AtomicInteger (0);
	
	public SocketBuffer (int size) {
		this._buf = new byte[size];
                this._users.lazySet (1);
		this._head = 0;
		this._data = this._tail = this._head;
		this._end  = size;
	}
        
        public void setSocket (hk.miru.javapgm.Socket socket) {
                this._socket = socket;
        }

	public long getTimestamp() {
		return this._timestamp;
	}

	public void setTimestamp (long timestamp) {
		this._timestamp = timestamp;
	}

	public void setTransportSessionId (TransportSessionId tsi) {
                checkNotNull (tsi);
		this._tsi = tsi;
	}

	public TransportSessionId getTransportSessionId() {
		return this._tsi;
	}

	public SequenceNumber getSequenceNumber() {
		return this._sequence;
	}

	public void setSequenceNumber (SequenceNumber sequence) {
                checkNotNull (sequence);
		this._sequence = sequence;
	}
        
        public int getUsers() {
                return this._users.get();
        }

	@Override
	public boolean equals (@Nullable Object obj) {
		if (obj instanceof SocketBuffer) {
			SocketBuffer other = (SocketBuffer)obj;
			return getSequenceNumber().equals (other.getSequenceNumber());
		}
		return false;
	}

/* TODO: hashCode has limited meaning with nullable or wait-state SKBs.
 */
        
	public byte[] getRawBytes() {
		return this._buf;
	}

	public int getDataOffset() {
		return this._data;
	}

	public int getLength() {
		return this._len;
	}

/* Increase reference count */        
        public SocketBuffer get() {
                this._users.incrementAndGet();
                return this;
        }
        
        public void free() {
                this._users.decrementAndGet();
        }
        
/* Add data */        
	public void put (int len) {
		this._tail += len;
		this._len += len;
                checkArgument (this._tail <= this._end);
	}

/* Remove data from start of buffer */        
	public void pull (int len) {
		this._len -= len;
		this._data += len;
	}

	public void reserve (int len) {
		this._data += len;
		this._tail += len;
                checkArgument (this._tail <= this._end);
                checkArgument (this._data >= this._head);
	}

	public Header getHeader() {
		return this._header;
	}

	public void setHeaderOffset (int offset) {
		this._header = new Header (this, offset);
	}

	public OriginalData getAsOriginalData() {
		return this._odata;
	}
        
        public RepairData getAsRepairData() {
                return new RepairData (this, this._odata._offset);
        }

	public void setOriginalDataOffset (int offset) {
		this._odata = new OriginalData (this, offset);
	}

	public void setFragmentOptionOffset (int offset) {
		this._opt_fragment = new OptionFragment (this, offset);
	}

	public final boolean isFragment() {
		return (null != this._opt_fragment);
	}

	public final OptionFragment getFragmentOption() {
		return this._opt_fragment;
	}

	public ControlBuffer getControlBuffer() {
		return this._cb;
	}

	public void setControlBuffer (@Nullable ControlBuffer cb) {
		this._cb = cb;
	}

	public final byte getSignedByte (int offset) {
		return this._buf[offset];
	}

	public final int getUnsignedByte (int offset) {
		return this._buf[offset] & 0xff;
	}

	public void setUnsignedByte (int offset, int value) {
		this._buf[offset] = (byte)(value & 0xff);
	}

	public final short getSignedShort (int offset) {
		return (short)((getSignedByte (offset + 0) << 8) +         /* keep sign-bit */
                             (getUnsignedByte (offset + 1) << 0));
	}

	public final int getUnsignedShort (int offset) {
		return ((getUnsignedByte (offset + 0) << 8) +
                        (getUnsignedByte (offset + 1) << 0));
	}

	public void setUnsignedShort (int offset, int value) {
		this._buf[offset + 0] = (byte)((value >> 8) & 0xff);
		this._buf[offset + 1] = (byte)((value >> 0) & 0xff);
	}

	public final int getSignedInt (int offset) {
		return (getSignedByte (offset + 0) << 24) +                /* keep sign-bit */
                     (getUnsignedByte (offset + 1) << 16) +
                     (getUnsignedByte (offset + 2) <<  8) +
                     (getUnsignedByte (offset + 3) <<  0);
	}

	public final long getUnsignedInt (int offset) {
		return ((long)(getUnsignedByte (offset + 0) << 24) +
                              (getUnsignedByte (offset + 1) << 16) +
                              (getUnsignedByte (offset + 2) <<  8) +
                              (getUnsignedByte (offset + 3) <<  0));
	}

	public void setUnsignedInt (int offset, long value) {
		this._buf[offset + 0] = (byte)((value >> 24) & 0xff);
		this._buf[offset + 1] = (byte)((value >> 16) & 0xff);
		this._buf[offset + 2] = (byte)((value >> 8) & 0xff);
		this._buf[offset + 3] = (byte)((value >> 0) & 0xff);
	}

        @SuppressWarnings("ShiftOutOfRange")
	public final long getSignedLong (int offset) {
		return ((long)getSignedByte (offset + 0) << 56) +          /* keep sign-bit */
                     (long)(getUnsignedByte (offset + 1) << 48) +
                     (long)(getUnsignedByte (offset + 2) << 40) +
                     (long)(getUnsignedByte (offset + 3) << 32) +
                     (long)(getUnsignedByte (offset + 4) << 24) +
                           (getUnsignedByte (offset + 5) << 16) +
                           (getUnsignedByte (offset + 6) <<  8) +
                           (getUnsignedByte (offset + 7) <<  0);
	}

        public static boolean isValid (SocketBuffer skb) {
                if (null == skb) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB is null.");
                        return false;
                }
/* Socket */                
                if (null == skb._socket) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB socket is not defined.");                    
                        return false;
                }
/* Timestamp */
                if (skb._timestamp <= 0) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB timestamp is not defined.");
                        return false;
                }
/* TSI */
/* Sequence can be any value */
/* ControlBuffer can be any value */
/* Length can be any value */
/* Pointers */
                if (skb._head < 0) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB head pointer is not defined.");
                        return false;
                }
                if (skb._data < 0) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB data pointer is not defined.");
                        return false;
                }
                if (skb._tail < 0) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB tail pointer is not defined.");
                        return false;
                }
                if (skb._data > skb._tail) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB data pointer beyond tail.");
                        return false;
                }
                if (skb._len != (skb._tail - skb._data)) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB length mismatch to tail - data.");
                        return false;
                }
                if (skb._end < 0) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB end pointer not defined.");
                        return false;
                }
                if (skb._tail > skb._end) {
                        LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB tail point beyond end.");
                        return false;
                }
/* PGM header */
                if (null != skb._header) {
                        if (null == skb._odata) {
                                LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB odata is not defined in presence of header.");
                                return false;
                        }
                } else {
                        if (null != skb._odata) {
                                LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB odata defined without header.");
                                return false;
                        }
                        if (null != skb._opt_fragment) {
                                LogManager.getLogger (SocketBuffer.class.getName()).error ("SKB option fragment defined without header.");
                                return false;
                        }
                }
                return true;
        }

        @Override
	public String toString() {
		return	" { " +
			  "\"timestamp\": " + this._timestamp +
			", \"tsi\": \"" + this._tsi + "\"" +
			", \"sequence\": " + this._sequence +
			", \"len\": " + this._len +
			", \"header\": " + this._header +
			", \"odata\": " + this._odata +
			", \"opt_fragment\": " + this._opt_fragment +
			", \"buf\": { " +
				  "\"head\": " + this._head +
				", \"data\": " + this._data +
				", \"tail\": " + this._tail +
				", \"end\": " + this._end +
				", \"length\": " + this._buf.length +
			" }" +
			" }";
	}
}

/* eof */