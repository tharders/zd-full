/*
 * 
 */
package org.jivesoftware.wildfire.muc.spi;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.jivesoftware.util.FastDateFormat;
import org.jivesoftware.util.IMConfig;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.Log;
import org.jivesoftware.wildfire.*;
import org.jivesoftware.wildfire.LocationManager.ComponentIdentifier;
import org.jivesoftware.wildfire.auth.UnauthorizedException;
import org.jivesoftware.wildfire.container.BasicModule;
import org.jivesoftware.wildfire.disco.DiscoInfoProvider;
import org.jivesoftware.wildfire.disco.DiscoItemsProvider;
import org.jivesoftware.wildfire.disco.DiscoServerItem;
import org.jivesoftware.wildfire.disco.ServerItemsProvider;
import org.jivesoftware.wildfire.forms.DataForm;
import org.jivesoftware.wildfire.forms.FormField;
import org.jivesoftware.wildfire.forms.spi.XDataFormImpl;
import org.jivesoftware.wildfire.forms.spi.XFormFieldImpl;
import org.jivesoftware.wildfire.muc.*;
import org.jivesoftware.wildfire.stats.Statistic;
import org.jivesoftware.wildfire.stats.StatisticsManager;
import org.jivesoftware.wildfire.user.UserNotFoundException;
import org.xmpp.component.ComponentManager;
import org.xmpp.packet.*;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.Constants;
import com.zimbra.common.util.StringUtil;
import com.zimbra.common.util.ZimbraLog;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements the chat server as a cached memory resident chat server. The server is also
 * responsible for responding Multi-User Chat disco requests as well as removing inactive users from
 * the rooms after a period of time and to maintain a log of the conversation in the rooms that 
 * require to log their conversations. The conversations log is saved to the database using a 
 * separate process<p>
 *
 * Temporary rooms are held in memory as long as they have occupants. They will be destroyed after
 * the last occupant left the room. On the other hand, persistent rooms are always present in memory
 * even after the last occupant left the room. In order to keep memory clean of peristent rooms that
 * have been forgotten or abandonded this class includes a clean up process. The clean up process
 * will remove from memory rooms that haven't had occupants for a while. Moreover, forgotten or
 * abandoned rooms won't be loaded into memory when the Multi-User Chat service starts up.
 *
 * @author Gaston Dombiak
 */
public class MultiUserChatServerImpl extends BasicModule implements MultiUserChatServer,
        ServerItemsProvider, DiscoInfoProvider, DiscoItemsProvider, RoutableChannelHandler {

    private static final FastDateFormat dateFormatter = FastDateFormat
            .getInstance("yyyyMMdd'T'HH:mm:ss", TimeZone.getTimeZone("GMT+0"));

    /**
     * Statistics keys
     */
    private static final String roomsStatKey = "muc_rooms";
    private static final String occupantsStatKey = "muc_occupants";
    private static final String usersStatKey = "muc_users";
    private static final String incomingStatKey = "muc_incoming";
    private static final String outgoingStatKey = "muc_outgoing";
    private static final String trafficStatGroup = "muc_traffic";


    /**
     * The time to elapse between clearing of idle chat users.
     */
    private int user_timeout = 300000;
    /**
     * The number of milliseconds a user must be idle before he/she gets kicked from all the rooms.
     */
    private int user_idle = -1;
    /**
     * Task that kicks idle users from the rooms.
     */
    private UserTimeoutTask userTimeoutTask;
    
    /**
     * The number of messages to log on each run of the logging process.
     */
    private int log_batch_size = 50;
    /**
     * Task that flushes room conversation logs to the database.
     */
    private LogConversationTask logConversationTask;
    /**
     * the chat service's hostname
     */
    private String chatServiceName = null;
    
    /**
     * The XMPP routable JID for this service
     */
    private String chatServiceDomain = null;

    /**
     * chatrooms managed by this manager, table: key room name (String); value ChatRoom
     */
    private Map<String,MUCRoom> rooms = new ConcurrentHashMap<String,MUCRoom>();

    /**
     * chat users managed by this manager, table: key user jid (XMPPAddress); value ChatUser
     */
    private Map<JID, MUCUser> users = new ConcurrentHashMap<JID, MUCUser>();
    private HistoryStrategy historyStrategy;

    private RoutingTable routingTable = null;
    /**
     * The packet router for the server.
     */
    private PacketRouter router = null;
    /**
     * The handler of packets with namespace jabber:iq:register for the server.
     */
    private IQMUCRegisterHandler registerHandler = null;
    /**
     * The total time all agents took to chat *
     */
    public long totalChatTime;

    /**
     * Timer to monitor chatroom participants. If they've been idle for too long, probe for
     * presence.
     */
    private Timer timer = new Timer("MUC cleanup");

    /**
     * Bare jids of users that are allowed to create MUC rooms. An empty list means that anyone can 
     * create a room. 
     */
    private List<String> allowedToCreate = new CopyOnWriteArrayList<String>();

    /**
     * Bare jids of users that are system administrators of the MUC service. A sysadmin has the same
     * permissions as a room owner.
     */
    private List<String> sysadmins = new CopyOnWriteArrayList<String>();

    /**
     * Queue that holds the messages to log for the rooms that need to log their conversations.
     */
    private Queue<ConversationLogEntry> logQueue = new LinkedBlockingQueue<ConversationLogEntry>();

    /**
     * Task that removes rooms from memory that have been without activity for a period of time. A
     * room is considered without activity when no occupants are present in the room for a while.
     */
    private CleanupTask cleanupTask;
    /**
     * The time to elapse between each rooms cleanup. Default frequency is 60 minutes.
     */
    private final long cleanup_frequency = 60 * 60 * 1000;

    /**
     * Total number of received messages in all rooms since the last reset. The counter
     * is reset each time the Statistic makes a sampling.
     */
    private AtomicInteger inMessages = new AtomicInteger(0);
    /**
     * Total number of broadcasted messages in all rooms since the last reset. The counter
     * is reset each time the Statistic makes a sampling.
     */
    private AtomicLong outMessages = new AtomicLong(0);
    
    private boolean mServiceEnabled = false;

    /**
     * Create a new group chat server.
     */
    public MultiUserChatServerImpl() {
        super("Basic multi user chat server");
        historyStrategy = new HistoryStrategy(null);
    }

    public String getDescription() {
        return null;
    }

    public void process(Packet packet) throws UnauthorizedException, PacketException {
        // TODO Remove this method when moving MUC as a component and removing module code
        processPacket(packet);
    }

    public void processPacket(Packet packet) {
        if (!isServiceEnabled()) {
            return;
        }
        // The MUC service will receive all the packets whose domain matches the domain of the MUC
        // service. This means that, for instance, a disco request should be responded by the
        // service itself instead of relying on the server to handle the request.
        try {
            // Check if the packet is a disco request or a packet with namespace iq:register
            if (packet instanceof IQ) {
                if (process((IQ)packet)) {
                    return;
                }
            }
            // The packet is a normal packet that should possibly be sent to the room
            MUCUser user = getChatUser(packet.getFrom());
            user.process(packet);
        }
        catch (Exception e) {
            Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
        }
    }

    /**
     * Returns true if the IQ packet was processed. This method should only process disco packets
     * as well as jabber:iq:register packets sent to the MUC service.
     *
     * @param iq the IQ packet to process.
     * @return true if the IQ packet was processed.
     */
    private boolean process(IQ iq) {
        Element childElement = iq.getChildElement();
        String namespace = null;
        // Ignore IQs of type ERROR
        if (IQ.Type.error == iq.getType()) {
            return false;
        }
        if (iq.getTo().getResource() != null) {
            // Ignore IQ packets sent to room occupants
            return false;
        }
        if (childElement != null) {
            namespace = childElement.getNamespaceURI();
        }
        if ("jabber:iq:register".equals(namespace)) {
            IQ reply = registerHandler.handleIQ(iq);
            router.route(reply);
        }
        else if ("http://jabber.org/protocol/disco#info".equals(namespace)) {
            try {
                // TODO MUC should have an IQDiscoInfoHandler of its own when MUC becomes
                // a component
                IQ reply = XMPPServer.getInstance().getIQDiscoInfoHandler().handleIQ(iq);
                router.route(reply);
            }
            catch (UnauthorizedException e) {
                // Do nothing. This error should never happen
            }
        }
        else if ("http://jabber.org/protocol/disco#items".equals(namespace)) {
            try {
                // TODO MUC should have an IQDiscoItemsHandler of its own when MUC becomes
                // a component
                IQ reply = XMPPServer.getInstance().getIQDiscoItemsHandler().handleIQ(iq);
                router.route(reply);
            }
            catch (UnauthorizedException e) {
                // Do nothing. This error should never happen
            }
        }
        else {
            return false;
        }
        return true;
    }

    public void initialize(JID jid, ComponentManager componentManager) {

    }

    public void shutdown() {

    }

    public String getServiceDomain() {
        return chatServiceDomain;
    }

    public JID getAddress() {
        return new JID(null, getServiceDomain(), null);
    }

    /**
     * Probes the presence of any user who's last packet was sent more than 5 minute ago.
     */
    private class UserTimeoutTask extends TimerTask {
        /**
         * Remove any user that has been idle for longer than the user timeout time.
         */
        public void run() {
            checkForTimedOutUsers();
        }
    }

    private void checkForTimedOutUsers() {
        // Do nothing if this feature is disabled (i.e USER_IDLE equals -1)
        if (user_idle <= 0) {
            return;
        }
        final long deadline = System.currentTimeMillis() - user_idle;
        for (MUCUser user : users.values()) {
            try {
                if (user.getLastPacketTime() < deadline) {
                    // If user is not present in any room then remove the user from
                    // the list of users
                    if (!user.isJoined()) {
                        removeUser(user.getAddress());
                        continue;
                    }
                    // Kick the user from all the rooms that he/she had previuosly joined
                    Iterator<MUCRole> roles = user.getRoles();
                    MUCRole role;
                    MUCRoom room;
                    Presence kickedPresence;
                    while (roles.hasNext()) {
                        role = roles.next();
                        room = role.getChatRoom();
                        try {
                            kickedPresence =
                                    room.kickOccupant(user.getAddress(), null, null);
                            // Send the updated presence to the room occupants
                            room.send(kickedPresence);
                        }
                        catch (NotAllowedException e) {
                            // Do nothing since we cannot kick owners or admins
                        }
                    }
                }
            }
            catch (Throwable e) {
                Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
            }
        }
    }

    /**
     * Logs the conversation of the rooms that have this feature enabled.
     */
    private class LogConversationTask extends TimerTask {
        public void run() {
            try {
                logConversation();
            }
            catch (Throwable e) {
                Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
            }
        }
    }

    private void logConversation() {
        ConversationLogEntry entry;
        boolean success;
        for (int index = 0; index <= log_batch_size && !logQueue.isEmpty(); index++) {
            entry = logQueue.poll();
            if (entry != null) {
                success = MUCPersistenceManager.saveConversationLogEntry(entry);
                if (!success) {
                    logQueue.add(entry);
                }
            }
        }
    }

    /**
     * Logs all the remaining conversation log entries to the database. Use this method to force
     * saving all the conversation log entries before the service becomes unavailable.
     */
    private void logAllConversation() {
        ConversationLogEntry entry;
        while (!logQueue.isEmpty()) {
            entry = logQueue.poll();
            if (entry != null) {
                MUCPersistenceManager.saveConversationLogEntry(entry);
            }
        }
    }

    /**
     * Removes from memory rooms that have been without activity for a period of time. A room is
     * considered without activity when no occupants are present in the room for a while.
     */
    private class CleanupTask extends TimerTask {
        public void run() {
            try {
                cleanupRooms();
            }
            catch (Throwable e) {
                Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
            }
        }
    }

    private void cleanupRooms() {
        for (MUCRoom room : rooms.values()) {
            if (room.getEmptyDate() != null && room.getEmptyDate().before(getCleanupDate())) {
                removeChatRoom(room.getName());
            }
        }
    }
    
    public MUCRoom getChatRoom(String roomName, JID userjid) throws NotAllowedException {
        MUCRoom room;
        synchronized (roomName.intern()) {
            room = rooms.get(roomName);
            if (room == null) {
                room = new MUCRoomImpl(this, roomName, router);
                // If the room is persistent load the configuration values from the DB
                try {
                    // Try to load the room's configuration from the database (if the room is
                    // persistent but was added to the DB after the server was started up or the
                    // room may be an old room that was not present in memory)
                    MUCPersistenceManager.loadFromDB((MUCRoomImpl) room);
                }
                catch (IllegalArgumentException e) {
                    // The room does not exist so check for creation permissions
                    // Room creation is always allowed for sysadmin
                    if (isRoomCreationRestricted() &&
                            !sysadmins.contains(userjid.toBareJID())) {
                        // The room creation is only allowed for certain JIDs
                        if (!isUserAllowedToCreate(userjid.toBareJID())) {
                            // The user is not in the list of allowed JIDs to create a room so raise
                            // an exception
                            throw new NotAllowedException();
                        }
                    }
                    room.addFirstOwner(userjid.toBareJID());
                }
                rooms.put(roomName, room);
            }
        }
        return room;
    }

    public MUCRoom getChatRoom(String roomName) {
        MUCRoom room = rooms.get(roomName);
        if (room == null) {
            // Check if the room exists in the database and was not present in memory
            synchronized (roomName.intern()) {
                room = rooms.get(roomName);
                if (room == null) {
                    room = new MUCRoomImpl(this, roomName, router);
                    // If the room is persistent load the configuration values from the DB
                    try {
                        // Try to load the room's configuration from the database (if the room is
                        // persistent but was added to the DB after the server was started up or the
                        // room may be an old room that was not present in memory)
                        MUCPersistenceManager.loadFromDB((MUCRoomImpl) room);
                        rooms.put(roomName, room);
                    }
                    catch (IllegalArgumentException e) {
                        // The room does not exist so do nothing
                        room = null;
                    }
                }
            }
        }
        return room;
    }

    public List<MUCRoom> getChatRooms() {
        return new ArrayList<MUCRoom>(rooms.values());
    }

    public boolean hasChatRoom(String roomName) {
        return getChatRoom(roomName) != null;
    }

    public void removeChatRoom(String roomName) {
        final MUCRoom room = rooms.remove(roomName);
        if (room != null) {
            totalChatTime += room.getChatLength();
        }
    }

    public String getServiceName() {
        return chatServiceName;
    }

    public HistoryStrategy getHistoryStrategy() {
        return historyStrategy;
    }

    public void removeUser(JID jabberID) {
        MUCUser user = users.remove(jabberID);
        if (user != null) {
            Iterator<MUCRole> roles = user.getRoles();
            while (roles.hasNext()) {
                MUCRole role = roles.next();
                try {
                    role.getChatRoom().leaveRoom(role.getNickname());
                }
                catch (Exception e) {
                    Log.error(e);
                }
            }
        }
    }

    public MUCUser getChatUser(JID userjid) throws UserNotFoundException {
        if (router == null) {
            throw new IllegalStateException("Not initialized");
        }
        MUCUser user;
        synchronized (userjid.toString().intern()) {
            user = users.get(userjid);
            if (user == null) {
                user = new MUCUserImpl(this, router, userjid);
                users.put(userjid, user);
            }
        }
        return user;
    }

    public void serverBroadcast(String msg) throws UnauthorizedException {
        for (MUCRoom room : rooms.values()) {
            room.serverBroadcast(msg);
        }
    }

    /**
     * Returns the limit date after which rooms whithout activity will be removed from memory.
     *
     * @return the limit date after which rooms whithout activity will be removed from memory.
     */
    private Date getCleanupDate() {
        /**
         * Max number of hours that a persistent room may be empty before the service removes the
         * room from memory. Unloaded rooms will exist in the database and may be loaded by a user
         * request. Default time limit is: 30 days.
         */
        long emptyMs = ((long)IMConfig.XMPP_MUC_UNLOAD_EMPTY_HOURS.getInt())*(Constants.MILLIS_PER_HOUR);
        return new Date(System.currentTimeMillis() - emptyMs);
    }

    public boolean isSysadmin(String userJID) {
        return sysadmins.contains(userJID.toLowerCase());
    }

    public boolean isUserAllowedToCreate(String userJID) {
        if (this.isRoomCreationRestricted())
            return allowedToCreate.contains(userJID.toLowerCase());
        else
            return true;
    }
    
    /**
     * Returns the flag that indicates if the service should provide information about locked rooms
     * when handling service discovery requests.
     *
     * @return true if the service should provide information about locked rooms.
     */
    private boolean isAllowToDiscoverLockedRooms() {
        /**
         * Flag that indicates if the service should provide information about locked rooms when
         * handling service discovery requests.
         * Note: Setting this flag in false is not compliant with the spec. A user may try to join a
         * locked room thinking that the room doesn't exist because the user didn't discover it before.
         */
        return IMConfig.XMPP_MUC_DISCOVER_LOCKED.getBoolean();
    }

    private boolean isRoomCreationRestricted() {
        /**
         * Returns the permission policy for creating rooms. A true value means that not anyone can
         * create a room, only the JIDs listed in <code>allowedToCreate</code> are allowed to create
         * rooms.
         */
        return IMConfig.XMPP_MUC_RESTRCIT_ROOM_CREATION.getBoolean();
    }
    
    public void setStartupParameters(ComponentIdentifier identifier, HashMap<String, String> properties) {
        chatServiceName = identifier.serviceName;
        chatServiceDomain = identifier.serviceDomain;
    }
    
    public void initialize(XMPPServer server) {
        super.initialize(server);

        // Load the list of JIDs that are sysadmins of the MUC service
        String property = IMConfig.XMPP_MUC_SYSADMIN_JID.getString();
        String[] jids;
        if (property != null) {
            jids = property.split(",");
            for (String jid : jids) {
                sysadmins.add(jid.trim().toLowerCase());
            }
        }
        
        // load the list of JIDs that are allowed to create rooms
        property = IMConfig.XMPP_MUC_CREATE_JID.getString();
        if (property != null) {
            jids = property.split(",");
            for (String jid : jids) {
                allowedToCreate.add(jid.trim().toLowerCase());
            }
        }
        user_timeout = IMConfig.XMPP_MUC_TASKS_IDLE_USER_SWEEP.getInt();
        user_idle = IMConfig.XMPP_MUC_TASKS_IDLE_USER_TIMEOUT.getInt();
        
        // The time to elapse between logging the room conversations.
        long log_timeout = IMConfig.XMPP_MUC_TASKS_LOG_TIMEOUT.getInt();
        
        log_batch_size = IMConfig.XMPP_MUC_TASKS_LOG_BATCHSIZE.getInt();
        
        // Run through the users every 5 minutes after a 5 minutes server startup delay (default
        // values)
        userTimeoutTask = new UserTimeoutTask();
        timer.schedule(userTimeoutTask, user_timeout, user_timeout);
        // Log the room conversations every 5 minutes after a 5 minutes server startup delay
        // (default values)
        logConversationTask = new LogConversationTask();
        timer.schedule(logConversationTask, log_timeout, log_timeout);
        // Remove unused rooms from memory
        cleanupTask = new CleanupTask();
        timer.schedule(cleanupTask, cleanup_frequency, cleanup_frequency);

        routingTable = server.getRoutingTable();
        router = server.getPacketRouter();
        // Configure the handler of iq:register packets
        registerHandler = new IQMUCRegisterHandler(this);
    }

    public void start() {
        super.start();
        // Add the route to this service
        routingTable.addRoute(getAddress(), this);
        mServiceEnabled = true;
        XMPPServer.getInstance().getIQDiscoItemsHandler().addServerItemsProvider(this);
        ArrayList<String> params = new ArrayList<String>();
        params.clear();
        params.add(getServiceDomain());
        Log.info(LocaleUtils.getLocalizedString("startup.starting.muc", params));
        // Load all the persistent rooms to memory
        for (MUCRoom room : MUCPersistenceManager.loadRoomsFromDB(this, this.getCleanupDate(),
                router)) {
            rooms.put(room.getName().toLowerCase(), room);
        }
        // Add statistics
        addTotalRoomStats();
        addTotalOccupantsStats();
        addTotalConnectedUsers();
        addNumberIncomingMessages();
        addNumberOutgoingMessages();
    }

    public void stop() {
        super.stop();
        // Remove the route to this service
        XMPPServer.getInstance().getIQDiscoItemsHandler().removeServerItemsProvider(this);
        mServiceEnabled = false;
        routingTable.removeRoute(getAddress());
        timer.cancel();
        logAllConversation();
        // Remove the statistics.
        StatisticsManager.getInstance().removeStatistic(roomsStatKey);
        StatisticsManager.getInstance().removeStatistic(occupantsStatKey);
        StatisticsManager.getInstance().removeStatistic(usersStatKey);
        StatisticsManager.getInstance().removeStatistic(incomingStatKey);
        StatisticsManager.getInstance().removeStatistic(outgoingStatKey);

    }

    public boolean isServiceEnabled() {
        return mServiceEnabled;
    }

    public long getTotalChatTime() {
        return totalChatTime;
    }

    /**
     * Retuns the number of existing rooms in the server (i.e. persistent or not,
     * in memory or not).
     *
     * @return the number of existing rooms in the server.
     */
    public int getNumberChatRooms() {
        return rooms.size();
    }

    /**
     * Retuns the total number of occupants in all rooms in the server.
     *
     * @return the number of existing rooms in the server.
     */
    public int getNumberConnectedUsers() {
        int total = 0;
        for (MUCUser user : users.values()) {
            if (user.isJoined()) {
                total = total + 1;
            }
        }
        return total;
    }

    /**
     * Retuns the total number of users that have joined in all rooms in the server.
     *
     * @return the number of existing rooms in the server.
     */
    public int getNumberRoomOccupants() {
        int total = 0;
        for (MUCRoom room : rooms.values()) {
            total = total + room.getOccupantsCount();
        }
        return total;
    }

    public void logConversation(MUCRoom room, Message message, JID sender) {
        // Only log messages that have a subject or body. Otherwise ignore it.
        if (message.getSubject() != null || message.getBody() != null) {
            logQueue.add(new ConversationLogEntry(this, new Date(), room, message, sender));
        }
    }

    public void messageBroadcastedTo(int numOccupants) {
        // Increment counter of received messages that where broadcasted by one
        inMessages.incrementAndGet();
        // Increment counter of outgoing messages with the number of room occupants
        // that received the message
        outMessages.addAndGet(numOccupants);
    }

    public Iterator<DiscoServerItem> getItems() {
        // Check if the service is disabled. Info is not available when disabled.
        if (!isServiceEnabled()) {
            return null;
        }
        ArrayList<DiscoServerItem> items = new ArrayList<DiscoServerItem>();
        items.add(new DiscoServerItem() {
            public String getJID() {
                return getServiceDomain();
            }

            public String getName() {
                return getServiceName();
            }

            public String getAction() {
                return null;
            }

            public String getNode() {
                return null;
            }

            public DiscoInfoProvider getDiscoInfoProvider() {
                return MultiUserChatServerImpl.this;
            }

            public DiscoItemsProvider getDiscoItemsProvider() {
                return MultiUserChatServerImpl.this;
            }
        });
        return items.iterator();
    }

    public Iterator<Element> getIdentities(String name, String node, JID senderJID) {
        ArrayList<Element> identities = new ArrayList<Element>();
        name = ensureDomain(name);
        if (name == null && node == null) {
            // Answer the identity of the MUC service
            Element identity = DocumentHelper.createElement("identity");
            identity.addAttribute("category", "conference");
            identity.addAttribute("name", "Public Chatrooms");
            identity.addAttribute("type", "text");

            identities.add(identity);
        }
        else if (name != null && node == null) {
            // Answer the identity of a given room
            MUCRoom room = getChatRoom(name);
            if (room != null && canDiscoverRoom(room)) {
                Element identity = DocumentHelper.createElement("identity");
                identity.addAttribute("category", "conference");
                identity.addAttribute("name", room.getNaturalLanguageName());
                identity.addAttribute("type", "text");

                identities.add(identity);
            }
        }
        else if (name != null && "x-roomuser-item".equals(node)) {
            // Answer reserved nickname for the sender of the disco request in the requested room
            MUCRoom room = getChatRoom(name);
            if (room != null) {
                String reservedNick = room.getReservedNickname(senderJID.toBareJID());
                if (reservedNick != null) {
                    Element identity = DocumentHelper.createElement("identity");
                    identity.addAttribute("category", "conference");
                    identity.addAttribute("name", reservedNick);
                    identity.addAttribute("type", "text");

                    identities.add(identity);
                }
            }
        }
        return identities.iterator();
    }

    public Iterator<String> getFeatures(String name, String node, JID senderJID) {
        name = ensureDomain(name);
        ArrayList<String> features = new ArrayList<String>();
        if (name == null && node == null) {
            // Answer the features of the MUC service
            features.add("http://jabber.org/protocol/muc");
            features.add("http://jabber.org/protocol/disco#info");
            features.add("http://jabber.org/protocol/disco#items");
        }
        else if (name != null && node == null) {
            // Answer the features of a given room
            // TODO lock the room while gathering this info???
            MUCRoom room = getChatRoom(name);
            if (room != null && canDiscoverRoom(room)) {
                features.add("http://jabber.org/protocol/muc");
                // Always add public since only public rooms can be discovered
                features.add("muc_public");
                if (room.isMembersOnly()) {
                    features.add("muc_membersonly");
                }
                else {
                    features.add("muc_open");
                }
                if (room.isModerated()) {
                    features.add("muc_moderated");
                }
                else {
                    features.add("muc_unmoderated");
                }
                if (room.canAnyoneDiscoverJID()) {
                    features.add("muc_nonanonymous");
                }
                else {
                    features.add("muc_semianonymous");
                }
                if (room.isPasswordProtected()) {
                    features.add("muc_passwordprotected");
                }
                else {
                    features.add("muc_unsecured");
                }
                if (room.isPersistent()) {
                    features.add("muc_persistent");
                }
                else {
                    features.add("muc_temporary");
                }
            }
        }
        return features.iterator();
    }

    public XDataFormImpl getExtendedInfo(String name, String node, JID senderJID) {
        name = ensureDomain(name);
        
        if (name != null && node == null) {
            // Answer the extended info of a given room
            // TODO lock the room while gathering this info???
            MUCRoom room = getChatRoom(name);
            if (room != null && canDiscoverRoom(room)) {
                XDataFormImpl dataForm = new XDataFormImpl(DataForm.TYPE_RESULT);

                XFormFieldImpl field = new XFormFieldImpl("FORM_TYPE");
                field.setType(FormField.TYPE_HIDDEN);
                field.addValue("http://jabber.org/protocol/muc#roominfo");
                dataForm.addField(field);

                field = new XFormFieldImpl("muc#roominfo_description");
                field.setLabel(LocaleUtils.getLocalizedString("muc.extended.info.desc"));
                field.addValue(room.getDescription());
                dataForm.addField(field);

                field = new XFormFieldImpl("muc#roominfo_subject");
                field.setLabel(LocaleUtils.getLocalizedString("muc.extended.info.subject"));
                field.addValue(room.getSubject());
                dataForm.addField(field);

                field = new XFormFieldImpl("muc#roominfo_occupants");
                field.setLabel(LocaleUtils.getLocalizedString("muc.extended.info.occupants"));
                field.addValue(Integer.toString(room.getOccupantsCount()));
                dataForm.addField(field);

                /*field = new XFormFieldImpl("muc#roominfo_lang");
                field.setLabel(LocaleUtils.getLocalizedString("muc.extended.info.language"));
                field.addValue(room.getLanguage());
                dataForm.addField(field);*/

                field = new XFormFieldImpl("x-muc#roominfo_creationdate");
                field.setLabel(LocaleUtils.getLocalizedString("muc.extended.info.creationdate"));
                field.addValue(dateFormatter.format(room.getCreationDate()));
                dataForm.addField(field);

                return dataForm;
            }
        }
        return null;
    }

    public boolean hasInfo(String name, String node, JID senderJID) {
        name = ensureDomain(name);
        
        // Check if the service is disabled. Info is not available when disabled.
        if (!isServiceEnabled()) {
            return false;
        }
        if (name == null && node == null) {
            // We always have info about the MUC service
            return true;
        }
        else if (name != null && node == null) {
            // We only have info if the room exists
            return hasChatRoom(name);
        }
        else if (name != null && "x-roomuser-item".equals(node)) {
            // We always have info about reserved names as long as the room exists
            return hasChatRoom(name);
        }
        return false;
    }
    
    private String ensureDomain(String name) {
        if (name != null && name.indexOf('@')<0) {
            return name + '@' + getServiceDomain();
        } 
        return name;
    }

    public Iterator<Element> getItems(String name, String node, JID senderJID) {
        name = ensureDomain(name);
        
        // Check if the service is disabled. Info is not available when disabled.
        if (!isServiceEnabled()) {
            return null;
        }
        List<Element> answer = new ArrayList<Element>();
        if (name == null && node == null) {
            Element item;
            // Answer all the public rooms as items
            for (MUCRoom room : rooms.values()) {
                if (canDiscoverRoom(room)) {
                    item = DocumentHelper.createElement("item");
                    item.addAttribute("jid", room.getRole().getRoleAddress().toString());
                    item.addAttribute("name", room.getNaturalLanguageName());

                    answer.add(item);
                }
            }
        }
        else if (name != null && node == null) {
            // Answer the room occupants as items if that info is publicly available
            MUCRoom room = getChatRoom(name);
            if (room != null && canDiscoverRoom(room)) {
                Element item;
                for (MUCRole role : room.getOccupants()) {
                    // TODO Should we filter occupants that are invisible (presence is not broadcasted)?
                    item = DocumentHelper.createElement("item");
                    item.addAttribute("jid", role.getRoleAddress().toString());

                    answer.add(item);
                }
            }
        }
        return answer.iterator();
    }

    private boolean canDiscoverRoom(MUCRoom room) {
        // Check if locked rooms may be discovered
        if (!isAllowToDiscoverLockedRooms() && room.isLocked()) {
            return false;
        }
        return room.isPublicRoom();
    }

    /****************** Statistics code ************************/
    private void addTotalRoomStats() {
        // Register a statistic.
        Statistic statistic = new Statistic() {
            public String getName() {
                return LocaleUtils.getLocalizedString("muc.stats.active_group_chats.name");
            }

            public Type getStatType() {
                return Type.count;
            }

            public String getDescription() {
                return LocaleUtils.getLocalizedString("muc.stats.active_group_chats.desc");
            }

            public String getUnits() {
                return LocaleUtils.getLocalizedString("muc.stats.active_group_chats.units");
            }

            public double sample() {
                return getNumberChatRooms();
            }
        };
        StatisticsManager.getInstance().addStatistic(roomsStatKey, statistic);
    }

    private void addTotalOccupantsStats() {
        // Register a statistic.
        Statistic statistic = new Statistic() {
            public String getName() {
                return LocaleUtils.getLocalizedString("muc.stats.occupants.name");
            }

            public Type getStatType() {
                return Type.count;
            }

            public String getDescription() {
                return LocaleUtils.getLocalizedString("muc.stats.occupants.description");
            }

            public String getUnits() {
                return LocaleUtils.getLocalizedString("muc.stats.occupants.label");
            }

            public double sample() {
                return getNumberRoomOccupants();
            }
        };
        StatisticsManager.getInstance().addStatistic(occupantsStatKey, statistic);
    }

    private void addTotalConnectedUsers() {
        // Register a statistic.
        Statistic statistic = new Statistic() {
            public String getName() {
                return LocaleUtils.getLocalizedString("muc.stats.users.name");
            }

            public Type getStatType() {
                return Type.count;
            }

            public String getDescription() {
                return LocaleUtils.getLocalizedString("muc.stats.users.description");
            }

            public String getUnits() {
                return LocaleUtils.getLocalizedString("muc.stats.users.label");
            }

            public double sample() {
                return getNumberConnectedUsers();
            }
        };
        StatisticsManager.getInstance().addStatistic(usersStatKey, statistic);
    }

    private void addNumberIncomingMessages() {
        // Register a statistic.
        Statistic statistic = new Statistic() {
            public String getName() {
                return LocaleUtils.getLocalizedString("muc.stats.incoming.name");
            }

            public Type getStatType() {
                return Type.rate;
            }

            public String getDescription() {
                return LocaleUtils.getLocalizedString("muc.stats.incoming.description");
            }

            public String getUnits() {
                return LocaleUtils.getLocalizedString("muc.stats.incoming.label");
            }

            public double sample() {
                return inMessages.getAndSet(0);
            }
        };
        StatisticsManager.getInstance().addMultiStatistic(incomingStatKey, trafficStatGroup, statistic);
    }

    private void addNumberOutgoingMessages() {
        // Register a statistic.
        Statistic statistic = new Statistic() {
            public String getName() {
                return LocaleUtils.getLocalizedString("muc.stats.outgoing.name");
            }

            public Type getStatType() {
                return Type.rate;
            }

            public String getDescription() {
                return LocaleUtils.getLocalizedString("muc.stats.outgoing.description");
            }

            public String getUnits() {
                return LocaleUtils.getLocalizedString("muc.stats.outgoing.label");
            }

            public double sample() {
                return outMessages.getAndSet(0);
            }
        };
        StatisticsManager.getInstance().addMultiStatistic(outgoingStatKey, trafficStatGroup, statistic);
    }
}