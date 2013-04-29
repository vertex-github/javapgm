/* Receive window as a ring buffer.
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ReceiveWindow {

	public enum PacketState {
		PKT_ERROR_STATE,
		PKT_BACK_OFF_STATE,
		PKT_WAIT_NCF_STATE,
		PKT_WAIT_DATA_STATE,
		PKT_HAVE_DATA_STATE,
		PKT_HAVE_PARITY_STATE,
		PKT_COMMIT_DATA_STATE,
		PKT_LOST_DATA_STATE
	}

	public enum Returns {
		RXW_OK,
		RXW_INSERTED,
		RXW_APPENDED,
		RXW_UPDATED,
		RXW_MISSING,
		RXW_DUPLICATE,
		RXW_MALFORMED,
		RXW_BOUNDS,
		RXW_SLOW_CONSUMER,
		RXW_UNKNOWN
	}

	public static final long UINT32_MAX		= 4294967295L;
	public static final int MAX_FRAGMENTS		= 16;
	public static final int MAX_APDU		= MAX_FRAGMENTS * 1500;

	protected TransportSessionId		tsi;

	private class State implements ControlBuffer {
		long				nakBackoffExpiration;
		long				nakRepeatExpiration;
		long				repairDataExpiration;
		PacketState			pktState;
		int				nakTransmitCount;
		int				ncfRetryCount;
		int				dataRetryCount;
		boolean				isContiguous;

		public State (PacketState pktState)
		{
			this.pktState = pktState;
		}

		public State()
		{
			this (PacketState.PKT_ERROR_STATE);
		}
	}

	protected Queue<SocketBuffer>		nakBackoffQueue;
	protected Queue<SocketBuffer>		waitNakConfirmQueue;
	protected Queue<SocketBuffer>		waitDataQueue;

	protected long				lostCount;
	protected long				fragmentCount;
	protected long				parityCount;
	protected long				committedCount;

	protected int				max_tpdu;
	protected SequenceNumber		lead, trail;
	protected SequenceNumber		rxw_trail, rxw_trail_init;
	protected SequenceNumber		commitLead;
	protected boolean			isConstrained = true;
	protected boolean			isDefined = false;
	protected boolean			hasEvent = false;
	protected boolean			isFecAvailable = false;
	protected long				transmissionGroupSize;
	protected long				tgSqnShift;

	protected long				minFillTime;
	protected long				maxFillTime;
	protected long				minNakTransmitCount;
	protected long				maxNakTransmitCount;
	protected long				cumulativeLosses;
	protected long				bytesDelivered;
	protected long				messagesDelivered;

	protected int				size;
	protected int				alloc;
	protected SocketBuffer[]		pdata = null;

	public Queue<SocketBuffer> getNakBackoffQueue() {
		return this.nakBackoffQueue;
	}

	public static long getNakBackoffExpiration (SocketBuffer skb) {
		final State state = (State)skb.getControlBuffer();
		return state.nakBackoffExpiration;
	}

	public static void setNakBackoffExpiration (SocketBuffer skb, long expiration) {
		State state = (State)skb.getControlBuffer();
		state.nakBackoffExpiration = expiration;
	}

	public long firstNakBackoffExpiration() {
		return getNakBackoffExpiration (this.nakBackoffQueue.peek());
	}

	public void setBackoffState (SocketBuffer skb) {
		setPacketState (skb, PacketState.PKT_BACK_OFF_STATE);
	}

	public Queue<SocketBuffer> getWaitNakConfirmQueue() {
		return this.waitNakConfirmQueue;
	}

	public static long getNakRepeatExpiration (SocketBuffer skb) {
		final State state = (State)skb.getControlBuffer();
		return state.nakRepeatExpiration;
	}

	public static void setNakRepeatExpiration (SocketBuffer skb, long expiration) {
		State state = (State)skb.getControlBuffer();
		state.nakRepeatExpiration = expiration;
	}

	public long firstNakRepeatExpiration() {
		return getNakRepeatExpiration (this.waitNakConfirmQueue.peek());
	}

	public void setWaitNakConfirmState (SocketBuffer skb) {
		setPacketState (skb, PacketState.PKT_WAIT_NCF_STATE);
	}

	public Queue<SocketBuffer> getWaitDataQueue() {
		return this.waitDataQueue;
	}

	public static long getRepairDataExpiration (SocketBuffer skb) {
		final State state = (State)skb.getControlBuffer();
		return state.repairDataExpiration;
	}

	public long firstRepairDataExpiration() {
		return getRepairDataExpiration (this.waitDataQueue.peek());
	}

	public static void incrementNakTransmitCount (SocketBuffer skb) {
		State state = (State)skb.getControlBuffer();
		state.nakTransmitCount++;
	}

	public static void incrementNcfRetryCount (SocketBuffer skb) {
		State state = (State)skb.getControlBuffer();
		state.ncfRetryCount++;
	}

	public static long getNcfRetryCount (SocketBuffer skb) {
		State state = (State)skb.getControlBuffer();
		return state.ncfRetryCount;
	}

	public static void incrementDataRetryCount (SocketBuffer skb) {
		State state = (State)skb.getControlBuffer();
		state.dataRetryCount++;
	}

	public static long getDataRetryCount (SocketBuffer skb) {
		State state = (State)skb.getControlBuffer();
		return state.dataRetryCount;
	}

	private SocketBuffer peek (SequenceNumber sequence)
	{
		if (isEmpty())
			return null;

		if (sequence.gte (this.trail) && sequence.lte (this.lead))
		{
/* index into ArrayList must be an int not a long:
 * error: possible loss of precision
 */
			final int index = (int)(sequence.longValue() % getMaxLength());
			SocketBuffer skb = this.pdata[index];
			return skb;
		}

		return null;
	}

	private int getCommitLength()
	{
		return this.commitLead.minus (this.trail).intValue();
	}

	private boolean isCommitEmpty()
	{
		return getCommitLength() == 0;
	}

	private int getIncomingLength()
	{
		return this.lead.plus (1).minus (this.commitLead).intValue();
	}

	private boolean isIncomingEmpty()
	{
		return getIncomingLength() == 0;
	}

	private int getMaxLength()
	{
		return this.alloc;
	}

	private int getLength()
	{
		return this.lead.plus (1).minus (this.trail).intValue();
	}

	private int getSize()
	{
		return this.size;
	}

	private boolean isEmpty()
	{
		return getLength() == 0;
	}

	private boolean isFull()
	{
		return getLength() == getMaxLength();
	}

	public ReceiveWindow (
		TransportSessionId tsi,
		int tpdu_size,			/* unsigned 16-bit */
		int sqns,			/* unsigned */
		int secs,			/* unsigned */
		long max_rte
		)
	{
		final int alloc_sqns = sqns > 0 ? sqns : (int)((secs * max_rte) / tpdu_size);
		this.pdata = new SocketBuffer[alloc_sqns];

		this.tsi = tsi;
		this.max_tpdu = tpdu_size;

System.out.println ("alloc_sqns:" + alloc_sqns);

/* empty state:
 *
 * trail = 0, lead = -1
 * commit_trail = commit_lead = rxw_trail = rxw_trail_init = 0
 */
		this.lead = SequenceNumber.MAX_VALUE;
		this.trail = this.lead.plus (1);

		this.commitLead = SequenceNumber.ZERO;
		this.rxw_trail = SequenceNumber.ZERO;
		this.rxw_trail_init = SequenceNumber.ZERO;

		this.tgSqnShift = 0;

/* RxPacket array */
		this.alloc = alloc_sqns;

/* Concurrent to permit modification during iteration without a listIterator. */
		this.nakBackoffQueue = new ConcurrentLinkedQueue<SocketBuffer> ();
		this.waitNakConfirmQueue = new ConcurrentLinkedQueue<SocketBuffer> ();
		this.waitDataQueue = new ConcurrentLinkedQueue<SocketBuffer> ();
	}

/* Returns:
 * PGM_RXW_INSERTED - packet filled a waiting placeholder, skb consumed.
 * PGM_RXW_APPENDED - packet advanced window lead, skb consumed.
 * PGM_RXW_MISSING - missing packets detected whilst window lead was adanced, skb consumed.
 * PGM_RXW_DUPLICATE - re-transmission of previously seen packet.
 * PGM_RXW_MALFORMED - corrupted or invalid packet.
 * PGM_RXW_BOUNDS - packet out of window.
 */
	public Returns add (SocketBuffer skb, long now, long nak_rb_expiry)
	{
		Returns status;

		System.out.println ("add ( " +
					"\"skb\": " + skb + "" +
					" )");

		skb.setControlBuffer (new State ());
		skb.setSequenceNumber (skb.getAsOriginalData().getSequenceNumber());

		if (skb.getLength() != skb.getHeader().getTsduLength()) {
			System.out.println ("SKB length does not match TSDU length.");
			return Returns.RXW_MALFORMED;
		}

/* protocol sanity check: valid trail pointer wrt. sequence */
		if (skb.getSequenceNumber().minus (skb.getAsOriginalData().getTrail()).longValue() >= ((UINT32_MAX/2)-1)) {
			System.out.println ("SKB sequence " + skb.getSequenceNumber() + " outside window horizon by " + skb.getSequenceNumber().minus (skb.getAsOriginalData().getTrail()) + " wrt trail " + skb.getAsOriginalData().getTrail());
			return Returns.RXW_BOUNDS;
		}

/* drop parity packets */
		if (skb.getHeader().isParity()) {
			System.out.println ("Unsupported parity packet");
			return Returns.RXW_MALFORMED;
		}

		if (skb.isFragment()) {
			if (skb.getFragmentOption().getApduLength() == skb.getLength()) {
				System.out.println ("Fragmented message contains only one fragment.");
			}
			if (skb.getFragmentOption().getApduLength() < skb.getLength()) {
				System.out.println ("SKB length greated than APDU length.");
				return Returns.RXW_MALFORMED;
			}
			if (skb.getFragmentOption().getFirstSequenceNumber().gt (skb.getSequenceNumber())) {
				System.out.println ("Fragment sequence number less than first message fragment.");
				return Returns.RXW_MALFORMED;
			}
			if (skb.getFragmentOption().getApduLength() > MAX_APDU) {
				System.out.println ("APDU greater than supported length.");
				return Returns.RXW_MALFORMED;
			}
		}

		if (!this.isDefined) {
			define (skb.getSequenceNumber().minus (1));
		} else {
			updateTrail (skb.getAsOriginalData().getTrail());
		}

		if (skb.getSequenceNumber().lt (this.commitLead)) {
			if (skb.getSequenceNumber().gte (this.trail)) {
				System.out.println ("Duplicate packet from window");
				return Returns.RXW_DUPLICATE;
			} else {
				System.out.println ("Duplicate packet before window");
				return Returns.RXW_BOUNDS;
			}
		}

System.out.println ("SKB:" + skb.getSequenceNumber() + " trail:" + this.trail + " commit:" + this.commitLead + " lead:" + this.lead + " (RXW_TRAIL:" + this.rxw_trail + ")");
		if (skb.getSequenceNumber().lte (this.lead)) {
			this.hasEvent = true;
			return insert (skb);
		}

		if (skb.getSequenceNumber().equals (this.lead.plus (1))) {
			this.hasEvent = true;
			return append (skb, now);
		}

		status = addPlaceholderRange (skb.getSequenceNumber(), now, nak_rb_expiry);
		if (Returns.RXW_APPENDED == status) {
			status = append (skb, now);
			if (Returns.RXW_APPENDED == status)
				status = Returns.RXW_MISSING;
		}

		return status;
	}

	private void define (SequenceNumber lead)
	{
System.out.println ("defining window");
		this.lead = lead;
		this.trail = this.lead.plus (1);
		this.rxw_trail_init = this.trail;
		this.rxw_trail = this.rxw_trail_init;
		this.commitLead = this.rxw_trail;
		this.isConstrained = this.isDefined = true;
	}

	public int update (SequenceNumber txw_lead, SequenceNumber txw_trail, long now, long nak_rb_expiry)
	{
		System.out.println ("update");

		if (!this.isDefined) {
			define (txw_trail);
			return 0;
		}

		updateTrail (txw_trail);
		return updateLead (txw_lead, now, nak_rb_expiry);
	}

	private void updateTrail (SequenceNumber txw_trail)
	{
System.out.println ("updating trail");
/* advertised trail is less than the current value */
		if (txw_trail.lte (this.rxw_trail))
			return;

/* protocol sanity check: advertised trail jumps too far ahead */
		if (txw_trail.minus (this.rxw_trail).longValue() > ((UINT32_MAX/2)-1))
			return;

/* retransmissions requests are constrained on startup until the advertised trail advances
 * beyond the first data sequence number.
 */
		if (this.isConstrained) {
			if (txw_trail.gt (this.rxw_trail_init))
				this.isConstrained = false;
			else
				return;
		}

		this.rxw_trail = txw_trail;

/* jump remaining sequence numbers if window is empty */
		if (isEmpty()) {
			final int distance = this.rxw_trail.minus (this.trail).intValue();
			this.trail = this.trail.plus (distance);
			this.commitLead = this.trail;
			this.lead = this.lead.plus (distance);

			this.cumulativeLosses += distance;
			return;
		}

/* remove all buffers between commit lead and advertised rxw_trail */
		for (SequenceNumber sequence = this.commitLead;
		     this.rxw_trail.gt (sequence) && this.lead.gte (sequence);
		     sequence = sequence.plus (1))
		{
System.out.println ("Purge #" + sequence);
			SocketBuffer skb = peek (sequence);
			State state = (State)skb.getControlBuffer();
			switch (state.pktState) {
			case PKT_HAVE_DATA_STATE:
			case PKT_HAVE_PARITY_STATE:
			case PKT_LOST_DATA_STATE:
				break;
			case PKT_ERROR_STATE:
				System.exit (-1);
			default:
				markLost (sequence);
				break;
			}
		}
	}

/* add one placeholder to leading edge due to detected lost packet.
 */
	private void addPlaceholder (long now, long nak_rb_expiry)
	{
/* advance lead */
		this.lead = this.lead.plus (1);

		SocketBuffer skb = new SocketBuffer (this.max_tpdu);
		skb.setControlBuffer (new State ());
		skb.setTimestamp (now);
		skb.setSequenceNumber (this.lead);
		State state = (State)skb.getControlBuffer();
		state.nakBackoffExpiration = nak_rb_expiry;

		if (!isFirstOfTransmissionGroup (this.lead)) {
			SocketBuffer first = peek (transmissionGroupSequenceNumber (this.lead));
			if (null != first) {
				State first_state = (State)first.getControlBuffer();
				first_state.isContiguous = false;
			}
		}

/* add skb to window */
		final int index = (int)(this.lead.longValue() % getMaxLength());
		this.pdata[index] = skb;

		setPacketState (skb, PacketState.PKT_BACK_OFF_STATE);
System.out.println ("Placeholder #" + this.lead + " @" + index);
	}

/* Returns:
 * RXW_BOUNDS: Incoming window is bound by commit window.
 * RXW_APPENDED: Place holders added.
 */
	private Returns addPlaceholderRange (SequenceNumber sequence, long now, long nak_rb_expiry)
	{
/* check bounds of commit window */
		final int commit_length = sequence.plus (1).minus (this.trail).intValue();
		if (!isCommitEmpty() && (commit_length >= getMaxLength())) {
			updateLead (sequence, now, nak_rb_expiry);
			return Returns.RXW_BOUNDS;
		}
		if (isFull()) {
			System.out.println ("Receive window full on placeholder sequence.");
			removeTrail();
		}
/* if packet is non-contiguous to current leading edge add place holders
 * TODO: can be rather inefficient on packet loss looping through dropped sequence numbers
 */
		while (!this.lead.plus (1).equals (sequence)) {
			addPlaceholder (now, nak_rb_expiry);
			if (isFull()) {
				System.out.println ("Receive window full on placeholder sequence.");
				removeTrail();
			}
		}
		return Returns.RXW_APPENDED;
	}

/* Returns number of place holders added.
 */
	private int updateLead (SequenceNumber txw_lead, long now, long nak_rb_expiry)
	{
		SequenceNumber lead = null;
		int lost = 0;

/* advertised lead is less than the current value */
		if (txw_lead.lte (this.lead))
			return 0;

/* committed packets limit constrain the lead until they are released */
		if (!isCommitEmpty() &&
		    txw_lead.minus (this.trail).intValue() >= getMaxLength())
		{
			lead = this.trail.plus (getMaxLength() - 1);
			if (lead.equals (this.lead))
				return 0;
		}
		else
			lead = txw_lead;

/* count lost sequences */
		while (!this.lead.equals (lead))
		{
/* slow consumer or fast producer */
			if (isFull()) {
				System.out.println ("Receive window full on window lead advancement.");
				removeTrail();
			}
			addPlaceholder (now, nak_rb_expiry);
			lost++;
		}

		return lost;
	}

/* Checks whether an APDU is unrecoverable due to lost TPDUs.
 */
	private boolean isApduLost (SocketBuffer skb)
	{
		State state = (State)skb.getControlBuffer();

/* lost is lost */
		if (PacketState.PKT_LOST_DATA_STATE == state.pktState)
			return true;

/* by definition, a single-TPDU APDU is complete */
		if (!skb.isFragment())
			return false;

		final SequenceNumber apdu_first_sequence = skb.getFragmentOption().getFirstSequenceNumber();

/* by definition, first fragment indicates APDU is available */
		if (apdu_first_sequence.equals (skb.getSequenceNumber()))
			return false;

		final SocketBuffer first_skb = peek (apdu_first_sequence);
/* first fragment out-of-bounds */
		if (null == first_skb)
			return true;

		state = (State)first_skb.getControlBuffer();
		if (PacketState.PKT_LOST_DATA_STATE == state.pktState)
			return true;

		return false;
	}

	private boolean isInvalidVarPktLen (SocketBuffer skb)
	{
/* FEC not available, always return valid. */
		return false;
	}

	private boolean hasPayloadOption (SocketBuffer skb)
	{
		return skb.isFragment() || skb.getHeader().isOptionEncoded();
	}

	private boolean isInvalidPayloadOption (SocketBuffer skb)
	{
/* FEC not available, always return valid. */
		return false;
	}

/* Returns:
 * PGM_RXW_INSERTED - packet filled a waiting placeholder, skb consumed.
 * PGM_RXW_DUPLICATE - re-transmission of previously seen packet.
 * PGM_RXW_MALFORMED - corrupted or invalid packet.
 * PGM_RXW_BOUNDS - packet out of window.
 */
	private Returns insert (SocketBuffer skb)
	{
System.out.println ("insert");
		State state = null;

		if (isInvalidVarPktLen (skb) || isInvalidPayloadOption (skb)) {
			System.out.println ("Invalid packet");
			return Returns.RXW_MALFORMED;
		}

		if (skb.getHeader().isParity()) {
			return Returns.RXW_MALFORMED;
		} else {
			final int index = (int)(skb.getSequenceNumber().longValue() % getMaxLength());
			skb = this.pdata[index];
			state = (State)skb.getControlBuffer();
			if (state.pktState == PacketState.PKT_HAVE_DATA_STATE)
				return Returns.RXW_DUPLICATE;
		}

/* APDU fragments are already declared lost */
		if (skb.isFragment() && isApduLost (skb)) {
			markLost (skb.getSequenceNumber());
			return Returns.RXW_BOUNDS;
		}

		switch (state.pktState) {
		case PKT_BACK_OFF_STATE:
		case PKT_WAIT_NCF_STATE:
		case PKT_WAIT_DATA_STATE:
		case PKT_LOST_DATA_STATE:
			break;
		case PKT_HAVE_PARITY_STATE:
			shuffleParity (skb);
			break;
		default:
			System.exit (-1);
			break;
		}

/* statistics */

/* replace placeholder skb with incoming skb */
		final int index = (int)(skb.getSequenceNumber().longValue() % getMaxLength());
		this.pdata[index] = skb;
		setPacketState (skb, PacketState.PKT_HAVE_DATA_STATE);
		this.size += skb.getLength();

		return Returns.RXW_INSERTED;
	}

	private void shuffleParity (SocketBuffer skb)
	{
/* no-op */
	}

/* Returns:
 * PGM_RXW_APPENDED - packet advanced window lead, skb consumed.
 * PGM_RXW_MALFORMED - corrupted or invalid packet.
 * PGM_RXW_BOUNDS - packet out of window.
 */
	private Returns append (SocketBuffer skb, long now)
	{
System.out.println ("append");
		if (isInvalidVarPktLen (skb) || isInvalidPayloadOption (skb)) {
			System.out.println ("Invalid packet");
			return Returns.RXW_MALFORMED;
		}

		if (isFull()) {
			if (isCommitEmpty()) {
				System.out.println ("Receive window full on new data, pulling trail.");
				removeTrail();
			} else {
				System.out.println ("Receive window full with commit data.");
				return Returns.RXW_BOUNDS;
			}
		}

/* advance leading edge */
		this.lead = this.lead.plus (1);

/* APDU fragments are already declared lost */
		if (skb.isFragment() && isApduLost (skb)) {
			SocketBuffer lost_skb = new SocketBuffer (this.max_tpdu);
			lost_skb.setControlBuffer (new State ());
			lost_skb.setTimestamp (now);
			lost_skb.setSequenceNumber (skb.getSequenceNumber());

/* add lost-placeholder skb to window */
			final int index = (int)(lost_skb.getSequenceNumber().longValue() % getMaxLength());
			this.pdata[index] = skb;

			setPacketState (skb, PacketState.PKT_LOST_DATA_STATE);
			System.out.println ("APDU already declared lost, ignoring TPDU.");
			return Returns.RXW_BOUNDS;
		}

/* add skb to window */
		if (skb.getHeader().isParity()) {
			return Returns.RXW_MALFORMED;
		} else {
			final int index = (int)(skb.getAsOriginalData().getSequenceNumber().longValue() % getMaxLength());
			this.pdata[index] = skb;
			setPacketState (skb, PacketState.PKT_HAVE_DATA_STATE);
		}

/* statistics */
		this.size += skb.getLength();
			
		return Returns.RXW_APPENDED;
	}

/* remove references to all commit packets not in the same transmission group
 * as the commit-lead
 */
	public void removeCommit()
	{
		final SequenceNumber tg_sqn_of_commit_lead = transmissionGroupSequenceNumber (this.commitLead);

		while (!isCommitEmpty() &&
			!tg_sqn_of_commit_lead.equals (transmissionGroupSequenceNumber (this.trail)))
		{
			removeTrail();
		}
	}

	public boolean hasCommitData()
	{
		return (this.committedCount > 0);
	}

/* flush packets but instead of calling on_data append the contiguous data packets
 * to the provided scatter/gather vector.
 *
 * when transmission groups are enabled, packets remain in the windows tagged committed
 * until the transmission group has been completely committed.  this allows the packet
 * data to be used in parity calculations to recover the missing packets.
 *
 * returns -1 on nothing read, returns length of bytes read, 0 is a valid read length.
 */
	public int read (List<SocketBuffer> skbs)
	{
System.out.println ("read");
		int bytes_read = -1;
		if (isIncomingEmpty())
			return bytes_read;
System.out.println ("read #" + this.commitLead);
		SocketBuffer skb = peek (this.commitLead);
		State state = (State)skb.getControlBuffer();
		switch (state.pktState) {
		case PKT_HAVE_DATA_STATE:
			bytes_read = incomingRead (skbs);
			break;
		case PKT_LOST_DATA_STATE:
/* do not purge in situ sequence */
			if (isCommitEmpty()) {
				System.out.println ("Removing lost trail from window");
				removeTrail();
			} else {
				System.out.println ("Locking trail at commit window");
			}
/* fall through */
		case PKT_BACK_OFF_STATE:
		case PKT_WAIT_NCF_STATE:
		case PKT_WAIT_DATA_STATE:
		case PKT_HAVE_PARITY_STATE:
			break;

		case PKT_COMMIT_DATA_STATE:
		case PKT_ERROR_STATE:
		default:
			System.exit (-1);
			break;
		}

		return bytes_read;
	}

/* remove lost sequences from the trailing edge of the window.  lost sequence
 * at lead of commit window invalidates all parity-data packets as any
 * transmission group is now unrecoverable.
 *
 * returns number of sequences purged.
 */
	private int removeTrail()
	{
System.out.println ("removeTrail");
		SocketBuffer skb = peek (this.trail);
System.out.println ("trail " + this.trail + " = " + skb);
		clearPacketState (skb);
		this.size -= skb.getLength();
/* remove reference to skb */
		skb = null;
		final boolean data_loss = this.trail.equals (this.commitLead);
		this.trail = this.trail.plus (1);
		if (data_loss) {
/* data-loss */
			this.commitLead = this.commitLead.plus (1);
			this.cumulativeLosses++;
			System.out.println ("Data loss due to pulled trailing edge, fragment count " + this.fragmentCount);
			return 1;
		}
		return 0;
	}

/* read contiguous APDU-grouped sequences from the incoming window.
 *
 * side effects:
 *
 * 1) increments statics for window messages and bytes read.
 *
 * returns count of bytes read.
 */
	private int incomingRead (List<SocketBuffer> skbs)
	{
		System.out.println ("incomingRead");
		int bytes_read = 0;
		int data_read = 0;

		do {
			SocketBuffer skb = peek (this.commitLead);
			if (isApduComplete (skb.isFragment() ? skb.getFragmentOption().getFirstSequenceNumber() : skb.getSequenceNumber()))
			{
				bytes_read += incomingReadApdu (skbs);
				data_read  ++;
			} else {
				break;
			}
		} while (!isIncomingEmpty());

		this.bytesDelivered    += bytes_read;
		this.messagesDelivered += data_read;
		return data_read > 0 ? bytes_read : -1;
	}

/* Returns TRUE if transmission group is lost.
 */
	private boolean isTgSqnLost (SequenceNumber tg_sqn)
	{
		if (isEmpty())
			return true;

		if (tg_sqn.lt (this.trail))
			return true;

		return false;
	}

/* check every TPDU in an APDU and verify that the data has arrived
 * and is available to commit to the application.
 *
 * if APDU sits in a transmission group that can be reconstructed use parity
 * data then the entire group will be decoded and any missing data packets
 * replaced by the recovery calculation.
 *
 * packets with single fragment fragment headers must be normalised as regular
 * packets before calling.
 *
 * APDUs exceeding PGM_MAX_FRAGMENTS or PGM_MAX_APDU length will be discarded.
 *
 * returns FALSE if APDU is incomplete or longer than max_len sequences.
 */
	private boolean isApduComplete (SequenceNumber firstSequence)
	{
		SocketBuffer skb = peek (firstSequence);
		if (null == skb)
			return false;

		final long apdu_size = skb.isFragment()? skb.getFragmentOption().getApduLength() : skb.getLength();
		final SequenceNumber tg_sqn = transmissionGroupSequenceNumber (firstSequence);

/* protocol sanity check: maximum length */
		if (apdu_size > MAX_APDU) {
			markLost (firstSequence);
			return false;
		}

		int contiguous_tpdus = 0;
		int contiguous_size = 0;

		for (SequenceNumber sequence = firstSequence;
		     null != skb;
		     skb = peek (sequence = sequence.plus (1)))
		{
			State state = (State)skb.getControlBuffer();

			if (PacketState.PKT_HAVE_DATA_STATE != state.pktState)
			{
				return false;
			}

/* single packet APDU, already complete */
			if (PacketState.PKT_HAVE_DATA_STATE == state.pktState && !skb.isFragment())
				return true;

/* protocol sanity check: matching first sequence reference */
			if (!skb.getFragmentOption().getFirstSequenceNumber().equals (firstSequence)) {
				markLost (firstSequence);
				return false;
			}

/* protocol sanity check: matching apdu length */
			if (skb.getFragmentOption().getApduLength() != apdu_size) {
				markLost (firstSequence);
				return false;
			}

/* protocol sanity check: maximum number of fragments per apdu */
			if (++contiguous_tpdus > MAX_FRAGMENTS) {
				markLost (firstSequence);
				return false;
			}

			contiguous_size += skb.getLength();
			if (apdu_size == contiguous_size)
				return true;
			else if (apdu_size < contiguous_size) {
				markLost (firstSequence);
				return false;
			}
		}

/* pending */
		return false;
	}

/* read one APDU consisting of one or more TPDUs.  target array is guaranteed
 * to be big enough to store complete APDU.
 */
	private int incomingReadApdu (List<SocketBuffer> skbs)
	{
		System.out.println ("incomingReadApdu");
		int contiguous_length = 0;
		int count = 0;
		SocketBuffer skb = peek (this.commitLead);
		final long apdu_len = skb.isFragment() ? skb.getFragmentOption().getApduLength() : skb.getLength();

		do {
			setPacketState (skb, PacketState.PKT_COMMIT_DATA_STATE);
			skbs.add (skb);
			contiguous_length += skb.getLength();
			this.commitLead = this.commitLead.plus (1);
			if (apdu_len == contiguous_length)
				break;
			skb = peek (this.commitLead);
		} while (apdu_len > contiguous_length);

		return contiguous_length;
	}

/* returns transmission group sequence (TG_SQN) from sequence (SQN).
 */
	private SequenceNumber transmissionGroupSequenceNumber (SequenceNumber sequence)
	{
		final long tg_sqn_mask = 0xffffffff << this.tgSqnShift;
		return SequenceNumber.valueOf (sequence.longValue() & tg_sqn_mask);
	}

	private int packetSequence (SequenceNumber sequence)
	{
		final long tg_sqn_mask = 0xffffffff << this.tgSqnShift;
		return (int)(sequence.longValue() & ~tg_sqn_mask);
	}

	private boolean isFirstOfTransmissionGroup (SequenceNumber sequence)
	{
		return packetSequence (sequence) == 0;
	}

	private boolean isLastOfTransmissionGroup (SequenceNumber sequence)
	{
		return packetSequence (sequence) == (this.transmissionGroupSize - 1);
	}

	private void setPacketState (SocketBuffer skb, PacketState newState)
	{
		State state = (State)skb.getControlBuffer();

		if (PacketState.PKT_ERROR_STATE != state.pktState)
			clearPacketState (skb);

		switch (newState) {
		case PKT_BACK_OFF_STATE:
			this.nakBackoffQueue.offer (skb);
			break;
		case PKT_WAIT_NCF_STATE:
			this.waitNakConfirmQueue.offer (skb);
			break;
		case PKT_WAIT_DATA_STATE:
			this.waitDataQueue.offer (skb);
			break;
		case PKT_HAVE_DATA_STATE:
			this.fragmentCount++;
			break;
		case PKT_HAVE_PARITY_STATE:
			this.parityCount++;
			break;
		case PKT_COMMIT_DATA_STATE:
			this.committedCount++;
			break;
		case PKT_LOST_DATA_STATE:
			this.lostCount++;
			this.cumulativeLosses++;
			this.hasEvent = true;
			break;
		case PKT_ERROR_STATE:
			break;
		default:
			System.exit (-1);
		}

		state.pktState = newState;
	}

	private void clearPacketState (SocketBuffer skb)
	{
		State state = (State)skb.getControlBuffer();

		switch (state.pktState) {
		case PKT_BACK_OFF_STATE:
			this.nakBackoffQueue.remove (skb);
			break;
		case PKT_WAIT_NCF_STATE:
			this.waitNakConfirmQueue.remove (skb);
			break;
		case PKT_WAIT_DATA_STATE:
			this.waitDataQueue.remove (skb);
			break;
		case PKT_HAVE_DATA_STATE:
			this.fragmentCount--;
			break;
		case PKT_HAVE_PARITY_STATE:
			this.parityCount--;
			break;
		case PKT_COMMIT_DATA_STATE:
			this.committedCount--;
			break;
		case PKT_LOST_DATA_STATE:
			this.lostCount--;
			break;
		case PKT_ERROR_STATE:
			break;
		default:
			System.exit (-1);
		}

		state.pktState = PacketState.PKT_ERROR_STATE;
	}

/* mark an existing sequence lost due to failed recovery.
 */
	public void markLost (SequenceNumber sequence)
	{
		System.out.println ("markLost ( " +
					"\"sequence\": " + sequence + "" +
					" )");
		SocketBuffer skb = peek (sequence);
		setPacketState (skb, PacketState.PKT_LOST_DATA_STATE);
	}

	private int confirm (SequenceNumber sequence)
	{
		return -1;
	}

	private int recoveryUpdate (SequenceNumber sequence)
	{
		return -1;
	}

	private int recoveryAppend()
	{
		return -1;
	}

	public boolean hasEvent()
	{
		return this.hasEvent;
	}

	public void clearEvent()
	{
		this.hasEvent = false;
	}

	public long getCumulativeLosses()
	{
		return this.cumulativeLosses;
	}

	public String toString() {
		return	"{" +
				  "\"lead\": " + this.lead + "" +
				", \"trail\": " + this.trail + "" +
				", \"RXW_TRAIL\": " + this.rxw_trail + "" +
				", \"RXW_TRAIL_INIT\": " + this.rxw_trail_init + "" +
				", \"commitLead\": " + this.commitLead + "" +
				", \"isConstrained\": " + this.isConstrained + "" +
				", \"isDefined\": " + this.isDefined + "" +
				", \"hasEvent\": " + this.hasEvent + "" +
				", \"size\": " + this.size + "" +
			"}";
	}

}

/* eof */
