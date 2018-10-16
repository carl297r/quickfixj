/*******************************************************************************
 * Copyright (c) quickfixengine.org  All rights reserved.
 *
 * This file is part of the QuickFIX FIX Engine
 *
 * This file may be distributed under the terms of the quickfixengine.org
 * license as defined by quickfixengine.org and appearing in the file
 * LICENSE included in the packaging of this file.
 *
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE.
 *
 * See http://www.quickfixengine.org/LICENSE for licensing information.
 *
 * Contact ask@quickfixengine.org if any conditions of this licensing
 * are not clear to you.
 ******************************************************************************/

package quickfix.examples.banzai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42tt.MarketDataRequest;
import quickfix.fix42tt.MarketDataSnapshot;

import javax.swing.*;
import java.math.BigDecimal;
import java.util.*;

public class BanzaiApplication implements Application {
    private static final Logger log = LoggerFactory.getLogger(BanzaiApplication.class);
    private static final String sessionPasswordKey = "SessionPassword";
    private SessionSettings sessionSettings = null;

    private final DefaultMessageFactory messageFactory = new DefaultMessageFactory();
    private OrderTableModel orderTableModel = null;
    private ExecutionTableModel executionTableModel = null;
    private final ObservableOrder observableOrder = new ObservableOrder();
    private final ObservableLogon observableLogon = new ObservableLogon();
    private boolean isAvailable = true;
    private boolean isMissingField;

    static private final TwoWayMap sideMap = new TwoWayMap();
    static private final TwoWayMap typeMap = new TwoWayMap();
    static private final TwoWayMap tifMap = new TwoWayMap();
    static private final HashMap<SessionID, HashSet<ExecID>> execIDs = new HashMap<>();

    public BanzaiApplication(
            SessionSettings sessionSettings,
            OrderTableModel orderTableModel,
            ExecutionTableModel executionTableModel) {

        this.sessionSettings = sessionSettings;
        this.orderTableModel = orderTableModel;
        this.executionTableModel = executionTableModel;
    }

    public void onCreate(SessionID sessionID) {
    }

    public void onLogon(SessionID sessionID) {
        observableLogon.logon(sessionID);
    }

    public void onLogout(SessionID sessionID) {
        observableLogon.logoff(sessionID);
    }

    public void toAdmin(quickfix.Message message, SessionID sessionID) {

        MsgType msgType = new MsgType();

        try {
            message.getHeader().getField(msgType);

            if(msgType.getValue() == MsgType.LOGON && sessionSettings.isSetting(sessionID, sessionPasswordKey))
            {
                String password = sessionSettings.getString(sessionID, sessionPasswordKey);
                message.setField(new quickfix.field.RawData(password));

//                message.setField(new quickfix.field.MsgSeqNum(1));
//                message.setField(new quickfix.field.ResetSeqNumFlag(true));
            }
        } catch (FieldNotFound e) {
            log.error("Can't find MsgType field:" + e.getMessage());
        } catch (FieldConvertError | ConfigError e) {
            log.error("Can't get password:" + e.getMessage());
        }

    }

    public void toApp(quickfix.Message message, SessionID sessionID) throws DoNotSend {
        message.getHeader().setField(new OnBehalfOfSubID("CLivermore"));
    }

    public void fromAdmin(quickfix.Message message, SessionID sessionID) throws FieldNotFound,
            IncorrectDataFormat, IncorrectTagValue, RejectLogon {
    }

    public void fromApp(quickfix.Message message, SessionID sessionID) throws FieldNotFound,
            IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        try {
            SwingUtilities.invokeLater(new MessageProcessor(message, sessionID));
        } catch (Exception e) {
        }
    }

    public class MessageProcessor implements Runnable {
        private final quickfix.Message message;
        private final SessionID sessionID;

        public MessageProcessor(quickfix.Message message, SessionID sessionID) {
            this.message = message;
            this.sessionID = sessionID;
        }

        public void run() {
            try {
                MsgType msgType = new MsgType();
                if (isAvailable) {
                    if (isMissingField) {
                        // For OpenFIX certification testing
                        sendBusinessReject(message, BusinessRejectReason.CONDITIONALLY_REQUIRED_FIELD_MISSING, "Conditionally required field missing");
                    }
                    else if (message.getHeader().isSetField(DeliverToCompID.FIELD)) {
                        // This is here to support OpenFIX certification
                        sendSessionReject(message, SessionRejectReason.COMPID_PROBLEM);
                    } else if (message.getHeader().getField(msgType).valueEquals("8")) {
                        executionReport(message, sessionID);
                    } else if (message.getHeader().getField(msgType).valueEquals("9")) {
                        cancelReject(message, sessionID);
                    } else if (message.getHeader().getField(msgType).valueEquals("B")) {
                        log.info("News message :" + message.toString());
                    } else if (message.getHeader().getField(msgType).valueEquals("j")) {
                        log.warn("Rejected message: " + message.toString());
//                    } else if (message.getHeader().getField(msgType).valueEquals("W")){
//                        log.info("Price Full: " + message.toString());
//                    } else if (message.getHeader().getField(msgType).valueEquals("X")) {
//                        log.info("Price Incremental: " + message.toString());
                    } else if (message.getHeader().getField(msgType).valueEquals("W")) {
                        marketDataFull(message, sessionID);
                    } else if (message.getHeader().getField(msgType).valueEquals("X")) {
                        marketDataIncremental(message, sessionID);
                    } else {
                        sendBusinessReject(message, BusinessRejectReason.UNSUPPORTED_MESSAGE_TYPE,
                                "Unsupported Message Type");
                    }
                } else {
                    sendBusinessReject(message, BusinessRejectReason.APPLICATION_NOT_AVAILABLE,
                            "Application not available");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void sendSessionReject(Message message, int rejectReason) throws FieldNotFound,
            SessionNotFound {
        Message reply = createMessage(message, MsgType.REJECT);
        reverseRoute(message, reply);
        String refSeqNum = message.getHeader().getString(MsgSeqNum.FIELD);
        reply.setString(RefSeqNum.FIELD, refSeqNum);
        reply.setString(RefMsgType.FIELD, message.getHeader().getString(MsgType.FIELD));
        reply.setInt(SessionRejectReason.FIELD, rejectReason);
        Session.sendToTarget(reply);
    }

    private void sendBusinessReject(Message message, int rejectReason, String rejectText)
            throws FieldNotFound, SessionNotFound {
        Message reply = createMessage(message, MsgType.BUSINESS_MESSAGE_REJECT);
        reverseRoute(message, reply);
        String refSeqNum = message.getHeader().getString(MsgSeqNum.FIELD);
        reply.setString(RefSeqNum.FIELD, refSeqNum);
        reply.setString(RefMsgType.FIELD, message.getHeader().getString(MsgType.FIELD));
        reply.setInt(BusinessRejectReason.FIELD, rejectReason);
        reply.setString(Text.FIELD, rejectText);
        Session.sendToTarget(reply);
    }

    private Message createMessage(Message message, String msgType) throws FieldNotFound {
        return messageFactory.create(message.getHeader().getString(BeginString.FIELD), msgType);
    }

    private void reverseRoute(Message message, Message reply) throws FieldNotFound {
        reply.getHeader().setString(SenderCompID.FIELD,
                message.getHeader().getString(TargetCompID.FIELD));
        reply.getHeader().setString(TargetCompID.FIELD,
                message.getHeader().getString(SenderCompID.FIELD));
    }

    private void executionReport(Message message, SessionID sessionID) throws FieldNotFound {

        ExecID execID = (ExecID) message.getField(new ExecID());
        if (alreadyProcessed(execID, sessionID))
            return;

        Order order = orderTableModel.getOrder(message.getField(new ClOrdID()).getValue());
        if (order == null) {
            return;
        }

        if (message.isSetField(new OrderID())) order.setTheirID(message.getField(new OrderID()).getValue());
        if (message.isSetField(new TTClOrdID())) order.setTTClID(message.getField(new TTClOrdID()).getValue());
        // if (message.isSetField(new OrigClOrdID())) order.setOriginalID(message.getField(new OrigClOrdID()).getValue());

        if (message.isSetField(new OrderQty())) order.setQuantity((int) Math.round(message.getField(new OrderQty()).getValue()));
        if (message.isSetField(new Price())) order.setLimit(message.getField(new Price()).getValue());

        BigDecimal fillSize;

        if (message.isSetField(LastShares.FIELD)) {
            LastShares lastShares = new LastShares();
            message.getField(lastShares);
            fillSize = new BigDecimal("" + lastShares.getValue());
        } else {
            // > FIX 4.1
            LeavesQty leavesQty = new LeavesQty();
            message.getField(leavesQty);
            fillSize = new BigDecimal(order.getQuantity()).subtract(new BigDecimal("" + leavesQty.getValue()));
        }

        if (fillSize.compareTo(BigDecimal.ZERO) > 0) {
            order.setOpen(order.getOpen() - (int) Double.parseDouble(fillSize.toPlainString()));
            order.setExecuted(Double.parseDouble(message.getString(CumQty.FIELD)));
            order.setAvgPx(Double.parseDouble(message.getString(AvgPx.FIELD)));
        }

        OrdStatus ordStatus = (OrdStatus) message.getField(new OrdStatus());

        if (ordStatus.valueEquals(OrdStatus.REJECTED)) {
            order.setRejected(true);
            order.setOpen(0);
        } else if (ordStatus.valueEquals(OrdStatus.CANCELED)
                || ordStatus.valueEquals(OrdStatus.DONE_FOR_DAY)) {
            order.setCanceled(true);
            order.setOpen(0);
        } else if (ordStatus.valueEquals(OrdStatus.NEW)) {
            if (order.isNew()) {
                order.setNew(false);
            }
        }

        try {
            order.setMessage(message.getField(new Text()).getValue());
        } catch (FieldNotFound e) {
        }

        orderTableModel.updateOrder(order, message.getField(new ClOrdID()).getValue());
        observableOrder.update(order);

        if (fillSize.compareTo(BigDecimal.ZERO) > 0) {
            Execution execution = new Execution();
            execution.setExchangeID(sessionID + message.getField(new ExecID()).getValue());

            execution.setSymbol(message.getField(new Symbol()).getValue());
            execution.setQuantity(fillSize.intValue());
            if (message.isSetField(LastPx.FIELD)) {
                execution.setPrice(Double.parseDouble(message.getString(LastPx.FIELD)));
            }
            Side side = (Side) message.getField(new Side());
            execution.setSide(FIXSideToSide(side));
            executionTableModel.addExecution(execution);
        }
    }

    private void cancelReject(Message message, SessionID sessionID) throws FieldNotFound {

        String id = message.getField(new ClOrdID()).getValue();
        Order order = orderTableModel.getOrder(id);
        if (order == null)
            return;
        if (order.getOriginalID() != null)
            order = orderTableModel.getOrder(order.getOriginalID());

        try {
            order.setMessage(message.getField(new Text()).getValue());
        } catch (FieldNotFound e) {
        }
        orderTableModel.updateOrder(order, message.getField(new OrigClOrdID()).getValue());
    }

    private boolean alreadyProcessed(ExecID execID, SessionID sessionID) {
        HashSet<ExecID> set = execIDs.get(sessionID);
        if (set == null) {
            set = new HashSet<>();
            set.add(execID);
            execIDs.put(sessionID, set);
            return false;
        } else {
            if (set.contains(execID))
                return true;
            set.add(execID);
            return false;
        }
    }

    private void send(quickfix.Message message, SessionID sessionID) {
        try {
            Session.sendToTarget(message, sessionID);
        } catch (SessionNotFound e) {
            System.out.println(e);
        }
    }

    private void marketDataFull(Message message, SessionID sessionID) throws FieldNotFound {
        MarketDataSnapshot.NoMDEntries mdEntriesGroup = new MarketDataSnapshot.NoMDEntries();
//        Group mDEntry = message.getGroup(1, mdEntriesGroup);
//        log.info("MDReqID: " + message.getField(new MDReqID()).getValue() +
//                " MarketData Type: " + mDEntry.getField(new MDEntryType()).getValue() +
//                " Price: " + mDEntry.getField(new MDEntryPx()).getValue() +
//                " Size: " + mDEntry.getField(new MDEntrySize()).getValue() +
////                " Orders: " + mDEntry.getField(new NumberOfOrders()).getValue() +
//                " Position " + mDEntry.getField(new MDEntryPositionNo()).getValue());

        final List<Group> mdEntries = message.getGroups(mdEntriesGroup.getFieldTag());
        for (Group mDEntry : mdEntries) {
            log.info("MDReqID: " + message.getField(new MDReqID()).getValue() +
                    " MarketData Type: " + mDEntry.getField(new MDEntryType()).getValue() +
                    " Price: " + mDEntry.getField(new MDEntryPx()).getValue() +
                    " Size: " + mDEntry.getField(new MDEntrySize()).getValue() +
                    " Orders: " + mDEntry.getField(new NumberOfOrders()).getValue() +
                    " Position " + mDEntry.getField(new MDEntryPositionNo()).getValue());
        }
    }

    private void marketDataIncremental(Message message, SessionID sessionID) throws FieldNotFound {
        MarketDataSnapshot.NoMDEntries mdEntriesGroup = new MarketDataSnapshot.NoMDEntries();
//        Group mDEntry = message.getGroup(1, mdEntriesGroup);
//        log.info("MDReqID: " + message.getField(new MDReqID()).getValue() +
//                " MarketData Type: " + mDEntry.getField(new MDEntryType()).getValue() +
//                " Price: " + mDEntry.getField(new MDEntryPx()).getValue() +
//                " Size: " + mDEntry.getField(new MDEntrySize()).getValue() +
////                " Orders: " + mDEntry.getField(new NumberOfOrders()).getValue() +
//                " Position " + mDEntry.getField(new MDEntryPositionNo()).getValue());
        final List<Group> mdEntries = message.getGroups(mdEntriesGroup.getFieldTag());
        for (Group mDEntry : mdEntries) {
            log.info("MDReqID: " + message.getField(new MDReqID()).getValue() +
                    " MarketData Type: " + mDEntry.getField(new MDEntryType()).getValue() +
                    " Price: " + mDEntry.getField(new MDEntryPx()).getValue() +
                    " Size: " + mDEntry.getField(new MDEntrySize()).getValue() +
                    " Orders: " + mDEntry.getField(new NumberOfOrders()).getValue() +
                    " Position " + mDEntry.getField(new MDEntryPositionNo()).getValue());
        }
    }

    public void marketData(Order order) {
        MarketDataRequest mDR = new MarketDataRequest(
                new MDReqID("1"),
                new SubscriptionRequestType(SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES)
        );

        mDR.set(new MarketDepth(10));
        mDR.set(new AggregatedBook(true));
        mDR.set(new MDUpdateType(MDUpdateType.INCREMENTAL_REFRESH));
        mDR.set(new IncludeNumberOfOrders('Y'));

        MarketDataRequest.NoMDEntryTypes noMDEntryTypes = new MarketDataRequest.NoMDEntryTypes();
        noMDEntryTypes.set(new MDEntryType(MDEntryType.BID));
        mDR.addGroup(noMDEntryTypes);
        noMDEntryTypes.set(new MDEntryType(MDEntryType.OFFER));
        mDR.addGroup(noMDEntryTypes);

        MarketDataRequest.NoRelatedSym noRelatedSym = new MarketDataRequest.NoRelatedSym();
        noRelatedSym.set(new SecurityExchange("CME"));
        noRelatedSym.set(new SecurityType("FUT"));
        noRelatedSym.set(new Symbol(order.getSymbol()));
        noRelatedSym.set(new MaturityMonthYear("201812"));
        mDR.addGroup(noRelatedSym);

        send(mDR, order.getSessionID());
    }

    public void send(Order order) {
        String beginString = order.getSessionID().getBeginString();
        switch (beginString) {
            case FixVersions.BEGINSTRING_FIX40:
                send40(order);
                break;
            case FixVersions.BEGINSTRING_FIX41:
                send41(order);
                break;
            case FixVersions.BEGINSTRING_FIX42:
                send42tt(order);
//                marketData(order);
                break;
            case FixVersions.BEGINSTRING_FIX43:
                send43(order);
                break;
            case FixVersions.BEGINSTRING_FIX44:
                send44(order);
                break;
            case FixVersions.BEGINSTRING_FIXT11:
                send50(order);
                break;
        }
    }

    public void send40(Order order) {
        quickfix.fix40.NewOrderSingle newOrderSingle = new quickfix.fix40.NewOrderSingle(
                new ClOrdID(order.getID()), new HandlInst('1'), new Symbol(order.getSymbol()),
                sideToFIXSide(order.getSide()), new OrderQty(order.getQuantity()),
                typeToFIXType(order.getType()));

        send(populateOrder(order, newOrderSingle), order.getSessionID());
    }

    public void send41(Order order) {
        quickfix.fix41.NewOrderSingle newOrderSingle = new quickfix.fix41.NewOrderSingle(
                new ClOrdID(order.getID()), new HandlInst('1'), new Symbol(order.getSymbol()),
                sideToFIXSide(order.getSide()), typeToFIXType(order.getType()));
        newOrderSingle.set(new OrderQty(order.getQuantity()));

        send(populateOrder(order, newOrderSingle), order.getSessionID());
    }

    public void send42(Order order) {
        quickfix.fix42.NewOrderSingle newOrderSingle = new quickfix.fix42.NewOrderSingle(
                new ClOrdID(order.getID()), new HandlInst('1'), new Symbol(order.getSymbol()),
                sideToFIXSide(order.getSide()), new TransactTime(), typeToFIXType(order.getType()));
        newOrderSingle.set(new OrderQty(order.getQuantity()));

        send(populateOrder(order, newOrderSingle), order.getSessionID());
    }

    public void send42tt(Order order) {
        quickfix.fix42tt.NewOrderSingle newOrderSingle = new quickfix.fix42tt.NewOrderSingle(
                new ClOrdID(order.getID()),
                // FixMe: CL - Exchange should come from order
                new SecurityExchange("CME"),
                //new Account("clivermore"),
                new Account(order.getAccount()),
                new OrderQty(order.getQuantity()),
                sideToFIXSide(order.getSide()),
                typeToFIXType(order.getType())
        );
        //newOrderSingle.set(new OrderQty(order.getQuantity()));
        newOrderSingle.set(new SecurityType("FUT"));
        newOrderSingle.set(new Symbol(order.getSymbol()));
        newOrderSingle.set(new MaturityMonthYear("201812"));
//        newOrderSingle.set(new SecurityID("2771194191319558797"));
//        newOrderSingle.getHeader().setField(new OnBehalfOfSubID("CLivermore"));

        send(populateOrder(order, newOrderSingle), order.getSessionID());
    }

    public void send43(Order order) {
        quickfix.fix43.NewOrderSingle newOrderSingle = new quickfix.fix43.NewOrderSingle(
                new ClOrdID(order.getID()), new HandlInst('1'), sideToFIXSide(order.getSide()),
                new TransactTime(), typeToFIXType(order.getType()));
        newOrderSingle.set(new OrderQty(order.getQuantity()));
        newOrderSingle.set(new Symbol(order.getSymbol()));
        send(populateOrder(order, newOrderSingle), order.getSessionID());
    }

    public void send44(Order order) {
        quickfix.fix44.NewOrderSingle newOrderSingle = new quickfix.fix44.NewOrderSingle(
                new ClOrdID(order.getID()), sideToFIXSide(order.getSide()),
                new TransactTime(), typeToFIXType(order.getType()));
        newOrderSingle.set(new OrderQty(order.getQuantity()));
        newOrderSingle.set(new Symbol(order.getSymbol()));
        newOrderSingle.set(new HandlInst('1'));
        send(populateOrder(order, newOrderSingle), order.getSessionID());
    }

    public void send50(Order order) {
        quickfix.fix50.NewOrderSingle newOrderSingle = new quickfix.fix50.NewOrderSingle(
                new ClOrdID(order.getID()), sideToFIXSide(order.getSide()),
                new TransactTime(), typeToFIXType(order.getType()));
        newOrderSingle.set(new OrderQty(order.getQuantity()));
        newOrderSingle.set(new Symbol(order.getSymbol()));
        newOrderSingle.set(new HandlInst('1'));
        send(populateOrder(order, newOrderSingle), order.getSessionID());
    }

    public quickfix.Message populateOrder(Order order, quickfix.Message newOrderSingle) {

        OrderType type = order.getType();

        if (type == OrderType.LIMIT)
            newOrderSingle.setField(new Price(order.getLimit()));
        else if (type == OrderType.STOP) {
            newOrderSingle.setField(new StopPx(order.getStop()));
        } else if (type == OrderType.STOP_LIMIT) {
            newOrderSingle.setField(new Price(order.getLimit()));
            newOrderSingle.setField(new StopPx(order.getStop()));
        }

        if (order.getSide() == OrderSide.SHORT_SELL
                || order.getSide() == OrderSide.SHORT_SELL_EXEMPT) {
            newOrderSingle.setField(new LocateReqd(false));
        }

        newOrderSingle.setField(tifToFIXTif(order.getTIF()));
        return newOrderSingle;
    }

    public void cancel(Order order) {
        String beginString = order.getSessionID().getBeginString();
        switch (beginString) {
            case "FIX.4.0":
                cancel40(order);
                break;
            case "FIX.4.1":
                cancel41(order);
                break;
            case "FIX.4.2":
                cancel42tt(order);
                break;
        }
    }

    public void cancel40(Order order) {
        String id = order.generateID();
        quickfix.fix40.OrderCancelRequest message = new quickfix.fix40.OrderCancelRequest(
                new OrigClOrdID(order.getID()), new ClOrdID(id), new CxlType(CxlType.FULL_REMAINING_QUANTITY), new Symbol(order
                        .getSymbol()), sideToFIXSide(order.getSide()), new OrderQty(order
                        .getQuantity()));

        orderTableModel.addID(order, id);
        send(message, order.getSessionID());
    }

    public void cancel41(Order order) {
        String id = order.generateID();
        quickfix.fix41.OrderCancelRequest message = new quickfix.fix41.OrderCancelRequest(
                new OrigClOrdID(order.getID()), new ClOrdID(id), new Symbol(order.getSymbol()),
                sideToFIXSide(order.getSide()));
        message.setField(new OrderQty(order.getQuantity()));

        orderTableModel.addID(order, id);
        send(message, order.getSessionID());
    }

    public void cancel42(Order order) {
        String id = order.generateID();
        quickfix.fix42.OrderCancelRequest message = new quickfix.fix42.OrderCancelRequest(
                new OrigClOrdID(order.getID()), new ClOrdID(id), new Symbol(order.getSymbol()),
                sideToFIXSide(order.getSide()), new TransactTime());
        message.setField(new OrderQty(order.getQuantity()));

        orderTableModel.addID(order, id);
        send(message, order.getSessionID());
    }

    public void cancel42tt(Order order) {
        String id = order.generateID();
        quickfix.fix42tt.OrderCancelRequest message = new quickfix.fix42tt.OrderCancelRequest(new ClOrdID(id));
        message.setField(new OrigClOrdID(order.getID()));
//                new OrigClOrdID(order.getID()));
//        , , new Symbol(order.getSymbol()),
//                sideToFIXSide(order.getSide()), new TransactTime());
//        message.setField(new OrderQty(order.getQuantity()));

        orderTableModel.addID(order, id);
        send(message, order.getSessionID());
    }

    public void replace(Order order, Order newOrder) {
        String beginString = order.getSessionID().getBeginString();
        switch (beginString) {
            case "FIX.4.0":
                replace40(order, newOrder);
                break;
            case "FIX.4.1":
                replace41(order, newOrder);
                break;
            case "FIX.4.2":
                replace42tt(order, newOrder);
                break;
        }
    }

    public void replace40(Order order, Order newOrder) {
        quickfix.fix40.OrderCancelReplaceRequest message = new quickfix.fix40.OrderCancelReplaceRequest(
                new OrigClOrdID(order.getID()), new ClOrdID(newOrder.getID()), new HandlInst('1'),
                new Symbol(order.getSymbol()), sideToFIXSide(order.getSide()), new OrderQty(
                        newOrder.getQuantity()), typeToFIXType(order.getType()));

        orderTableModel.addID(order, newOrder.getID());
        send(populateCancelReplace(order, newOrder, message), order.getSessionID());
    }

    public void replace41(Order order, Order newOrder) {
        quickfix.fix41.OrderCancelReplaceRequest message = new quickfix.fix41.OrderCancelReplaceRequest(
                new OrigClOrdID(order.getID()), new ClOrdID(newOrder.getID()), new HandlInst('1'),
                new Symbol(order.getSymbol()), sideToFIXSide(order.getSide()), typeToFIXType(order
                        .getType()));

        orderTableModel.addID(order, newOrder.getID());
        send(populateCancelReplace(order, newOrder, message), order.getSessionID());
    }

    public void replace42(Order order, Order newOrder) {
        quickfix.fix42.OrderCancelReplaceRequest message = new quickfix.fix42.OrderCancelReplaceRequest(
                new OrigClOrdID(order.getID()), new ClOrdID(newOrder.getID()), new HandlInst('1'),
                new Symbol(order.getSymbol()), sideToFIXSide(order.getSide()), new TransactTime(),
                typeToFIXType(order.getType()));

        orderTableModel.addID(order, newOrder.getID());
        send(populateCancelReplace(order, newOrder, message), order.getSessionID());
    }

    public void replace42tt(Order order, Order newOrder) {
        quickfix.fix42tt.OrderCancelReplaceRequest message = new quickfix.fix42tt.OrderCancelReplaceRequest(
                new ClOrdID(newOrder.getID()),
                new Account("clivermore"),
                new OrderQty(order.getQuantity()),
                sideToFIXSide(order.getSide()),
                typeToFIXType(order.getType()));

        message.setField(new OrigClOrdID(order.getID()));

        orderTableModel.addID(order, newOrder.getID());
        send(populateCancelReplace(order, newOrder, message), order.getSessionID());
    }

    Message populateCancelReplace(Order order, Order newOrder, quickfix.Message message) {

        //if (order.getQuantity() != newOrder.getQuantity())
            message.setField(new OrderQty(newOrder.getQuantity()));
        //if (!order.getLimit().equals(newOrder.getLimit()))
            message.setField(new Price(newOrder.getLimit()));
        return message;
    }

    public Side sideToFIXSide(OrderSide side) {
        return (Side) sideMap.getFirst(side);
    }

    public OrderSide FIXSideToSide(Side side) {
        return (OrderSide) sideMap.getSecond(side);
    }

    public OrdType typeToFIXType(OrderType type) {
        return (OrdType) typeMap.getFirst(type);
    }

    public OrderType FIXTypeToType(OrdType type) {
        return (OrderType) typeMap.getSecond(type);
    }

    public TimeInForce tifToFIXTif(OrderTIF tif) {
        return (TimeInForce) tifMap.getFirst(tif);
    }

    public OrderTIF FIXTifToTif(TimeInForce tif) {
        return (OrderTIF) typeMap.getSecond(tif);
    }

    public void addLogonObserver(Observer observer) {
        observableLogon.addObserver(observer);
    }

    public void deleteLogonObserver(Observer observer) {
        observableLogon.deleteObserver(observer);
    }

    public void addOrderObserver(Observer observer) {
        observableOrder.addObserver(observer);
    }

    public void deleteOrderObserver(Observer observer) {
        observableOrder.deleteObserver(observer);
    }

    private static class ObservableOrder extends Observable {
        public void update(Order order) {
            setChanged();
            notifyObservers(order);
            clearChanged();
        }
    }

    private static class ObservableLogon extends Observable {
        private final HashSet<SessionID> set = new HashSet<>();

        public void logon(SessionID sessionID) {
            set.add(sessionID);
            setChanged();
            notifyObservers(new LogonEvent(sessionID, true));
            clearChanged();
        }

        public void logoff(SessionID sessionID) {
            set.remove(sessionID);
            setChanged();
            notifyObservers(new LogonEvent(sessionID, false));
            clearChanged();
        }
    }

    static {
        sideMap.put(OrderSide.BUY, new Side(Side.BUY));
        sideMap.put(OrderSide.SELL, new Side(Side.SELL));
        sideMap.put(OrderSide.SHORT_SELL, new Side(Side.SELL_SHORT));
        sideMap.put(OrderSide.SHORT_SELL_EXEMPT, new Side(Side.SELL_SHORT_EXEMPT));
        sideMap.put(OrderSide.CROSS, new Side(Side.CROSS));
        sideMap.put(OrderSide.CROSS_SHORT, new Side(Side.CROSS_SHORT));

        typeMap.put(OrderType.MARKET, new OrdType(OrdType.MARKET));
        typeMap.put(OrderType.LIMIT, new OrdType(OrdType.LIMIT));
        typeMap.put(OrderType.STOP, new OrdType(OrdType.STOP));
        typeMap.put(OrderType.STOP_LIMIT, new OrdType(OrdType.STOP_LIMIT));

        tifMap.put(OrderTIF.DAY, new TimeInForce(TimeInForce.DAY));
        tifMap.put(OrderTIF.IOC, new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL));
        tifMap.put(OrderTIF.OPG, new TimeInForce(TimeInForce.AT_THE_OPENING));
        tifMap.put(OrderTIF.GTC, new TimeInForce(TimeInForce.GOOD_TILL_CANCEL));
        tifMap.put(OrderTIF.GTX, new TimeInForce(TimeInForce.GOOD_TILL_CROSSING));
    }

    public boolean isMissingField() {
        return isMissingField;
    }

    public void setMissingField(boolean isMissingField) {
        this.isMissingField = isMissingField;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public void setAvailable(boolean isAvailable) {
        this.isAvailable = isAvailable;
    }
}
