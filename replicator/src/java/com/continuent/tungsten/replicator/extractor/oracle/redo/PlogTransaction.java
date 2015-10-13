/**
 * VMware Continuent Tungsten Replicator
 * Copyright (C) 2015 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *      
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Initial developer(s): Vit Spinka
 * Contributor(s):
 */

package com.continuent.tungsten.replicator.extractor.oracle.redo;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;

import javax.sql.rowset.serial.SerialException;

import org.apache.log4j.Logger;

import com.continuent.tungsten.common.cache.LargeObjectArray;
import com.continuent.tungsten.common.cache.LargeObjectScanner;
import com.continuent.tungsten.common.cache.RawByteCache;
import com.continuent.tungsten.replicator.ReplicatorException;
import com.continuent.tungsten.replicator.database.Database;
import com.continuent.tungsten.replicator.dbms.DBMSData;
import com.continuent.tungsten.replicator.dbms.OneRowChange;
import com.continuent.tungsten.replicator.dbms.OneRowChange.ColumnSpec;
import com.continuent.tungsten.replicator.dbms.OneRowChange.ColumnVal;
import com.continuent.tungsten.replicator.dbms.RowChangeData;
import com.continuent.tungsten.replicator.dbms.RowChangeData.ActionType;
import com.continuent.tungsten.replicator.dbms.StatementData;
import com.continuent.tungsten.replicator.event.DBMSEvent;
import com.continuent.tungsten.replicator.event.ReplOptionParams;

/**
 * Represents one transaction. The LCR entries are stored in a large object
 * array, which is allocated on a RawByteCache instance. Transactions
 * <em>must</em> be released to avoid resource leaks that will cause the
 * replicator to fail.
 */
class PlogTransaction implements Comparable<PlogTransaction>
{
    private static Logger logger = Logger.getLogger(PlogTransaction.class);

    private final LargeObjectArray<PlogLCR> LCRList;
    final String                            XID;
    boolean                                 committed = false;
    boolean                                 empty     = true;
    java.sql.Timestamp                      commitTime;

    public long startSCN  = 0;
    public long commitSCN = 0;

    private int skipSeq = 0;

    public boolean transactionIsDML = true; // false=DDL, true=DML

    public long startPlogId = 0;

    /**
     * constructor: new transaction
     * 
     * @param XID = transaction id
     */
    public PlogTransaction(RawByteCache cache, String XID)
    {
        this.XID = XID;
        this.LCRList = new LargeObjectArray<PlogLCR>(cache);
    }

    /**
     * Release large object array, which frees cache resources. This
     * <em>must</em> be called to avoid resource leaks.
     */
    public void release()
    {
        LCRList.release();
    }

    /**
     * order transactions by XID (makes sense only if they have same commit SCN)
     * 
     * @param o other object to compare
     * @return Comparable.compareTo usual less/equals/more flag
     */
    public int compareTo(PlogTransaction o)
    {
        return this.XID.compareTo(o.XID);
    }

    /**
     * add new LCR to this transaction
     * 
     * @param LCR LCR to add
     */
    public void putLCR(PlogLCR LCR) throws ReplicatorException
    {
        if (startSCN == 0)
        {
            startSCN = LCR.SCN; /*
                                 * startSCN is the SCN of very first operation
                                 * in transaction
                                 */
        }

        if (startPlogId == 0)
        {
            startPlogId = LCR.getPlogId();
        }

        if (LCR.type == PlogLCR.ETYPE_TRANSACTIONS
                && LCR.subtype == PlogLCR.ESTYPE_TRAN_COMMIT)
        {
            // Commit: say we are done.
            committed = true;
            commitSCN = LCR.SCN;
            commitTime = LCR.timestamp;

        }
        else
            if (LCR.type == PlogLCR.ETYPE_TRANSACTIONS
                    && LCR.subtype == PlogLCR.ESTYPE_TRAN_ROLLBACK)
        {
            // Rollback tran: say we are done, but we are empty.
            // We may want to release LCRList but resizing to 0 is OK too.
            empty = true;
            LCRList.resize(0);
            committed = true;
        }
        else
                if (LCR.type == PlogLCR.ETYPE_TRANSACTIONS
                        && LCR.subtype == PlogLCR.ESTYPE_TRAN_ROLLBACK_TO_SAVEPOINT)
        {
            // Rollback to savepoint: discard rolled back changes by resizing to
            // before the last value that is at or above save point ID.
            int last = LCRList.size() - 1;
            while (last > 0 && LCRList.get(last).LCRid >= LCR.LCRSavepointId)
            {
                last--;
            }
            LCRList.resize(last);
        }
        else if (LCR.type == PlogLCR.ETYPE_LCR_DATA)
        {
            /* We have some data = we are not empty */
            empty = false;
            LCRList.add(LCR);
            if (LCR.subtype == PlogLCR.ESTYPE_LCR_DDL)
                this.transactionIsDML = false;
        }
    }

    /**
     * is this transaction committed?
     * 
     * @return boolean flag
     */
    public boolean isCommitted()
    {
        return committed;
    }

    /**
     * is this transaction empty?
     * 
     * @return boolean flag
     */
    public boolean isEmpty()
    {
        return empty;
    }

    /**
     * Set skip sequence - if this transaction is not be posted complete, set
     * the last sequence to skip (and *NOT* include)
     * 
     * @param skipSeq
     */
    public void setSkipSeq(int skipSeq)
    {
        this.skipSeq = skipSeq;
    }

    /**
     * Post all committed changes to given queue
     * 
     * @param q Queue to post
     * @param minSCN Minimal SCN among all open transactions
     * @param skipSeq If only part of the transaction should be posted, this is
     *            the last seq to skip
     * @param lastObsoletePlogSeq Last obsolete plog sequence (min() over all
     *            open transactions - 1)
     * @return lastProcessedEventId
     * @throws UnsupportedEncodingException
     */
    public String pushContentsToQueue(BlockingQueue<DBMSEvent> q, long minSCN,
            int transactionFragSize, long lastObsoletePlogSeq)
                    throws UnsupportedEncodingException, ReplicatorException,
                    SerialException, SQLException, InterruptedException
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("pushContentsToQueue: " + this.XID + " (DML:"
                    + this.transactionIsDML + ")");
        }

        if (transactionIsDML)
        {
            int count = 0;
            PlogLCR lastLCR = null;
            String lastProcessedEventId = null;
            ArrayList<DBMSData> data = new ArrayList<DBMSData>();
            int fragSize = 0;

            LargeObjectScanner<PlogLCR> scanner = null;
            try
            {
                scanner = LCRList.scanner();
                while (scanner.hasNext())
                {
                    PlogLCR LCR = scanner.next();
                    if (LCR.type == PlogLCR.ETYPE_LCR_DATA)
                    { /* add only data */
                        if (LCR.subtype == PlogLCR.ESTYPE_LCR_DDL)
                            throw new ReplicatorException(
                                    "Internal corruption: DDL statement in a DML transaction.");
                        if (transactionFragSize > 0
                                && fragSize >= transactionFragSize)
                        {
                            logger.debug("Fragmenting");
                            // Time to fragment
                            DBMSEvent event = new DBMSEvent(lastLCR.eventId,
                                    data, false, commitTime);

                            event.setMetaDataOption(ReplOptionParams.DBMS_TYPE,
                                    Database.ORACLE);
                            // Strings are converted to UTF8 rather than using
                            // bytes
                            // for this extractor.
                            event.setMetaDataOption(ReplOptionParams.STRINGS,
                                    "utf8");
                            q.put(event);

                            data = new ArrayList<DBMSData>(); /*
                                                               * clear array for
                                                               * next fragment
                                                               */
                            fragSize = 0;
                        }

                        count++;
                        fragSize++;
                        if (count > skipSeq)
                        {
                            LCR.eventId = "" + commitSCN + "#" + XID + "#"
                                    + count + "#" + minSCN + "#"
                                    + lastObsoletePlogSeq;
                            lastLCR = LCR;

                            data.add(convertLCRtoDBMSDataDML(LCR));
                            lastProcessedEventId = LCR.eventId;

                            {
                                PlogLCR r2 = LCR;
                                if (logger.isDebugEnabled())
                                {
                                    logger.debug("LCR: " + r2.type + "."
                                            + r2.subtype + ":"
                                            + r2.typeAsString() + ", XID="
                                            + r2.XID + ", LCRid=" + r2.LCRid
                                            + " eventId=" + r2.eventId);
                                    logger.debug(
                                            "EventId#1 set to " + r2.eventId);
                                }
                            }

                        }
                    }
                    else
                    {
                        throw new RuntimeException("Type " + LCR.type
                                + " in queue, should be only data");
                    }
                }
            }
            catch (IOException e)
            {
                logger.error("Transaction scanner error occured: XID="
                        + this.XID + " key=" + LCRList.getKey() + " cache="
                        + LCRList.getByteCache().toString());
                throw new RuntimeException(
                        "Unable to read transaction from cache: " + this.XID);
            }
            finally
            {
                if (scanner != null)
                    scanner.close();
            }
            if (lastLCR != null)
            { /* last LCR set = transaction is not empty */
                lastLCR.eventId = "" + commitSCN + "#" + XID + "#" + "LAST"
                        + "#" + minSCN + "#"
                        + lastObsoletePlogSeq; /*
                                                * mark last LCR as "LAST", so we
                                                * know transaction is complete
                                                */
                if (logger.isDebugEnabled())
                {
                    logger.debug("EventId#2 set to " + lastLCR.eventId);
                }

                DBMSEvent event = new DBMSEvent(lastLCR.eventId, data, true,
                        commitTime);
                event.setMetaDataOption(ReplOptionParams.DBMS_TYPE,
                        Database.ORACLE);
                // Strings are converted to UTF8 rather than using bytes for
                // this extractor.
                event.setMetaDataOption(ReplOptionParams.STRINGS, "utf8");

                q.put(event);
            }

            return lastProcessedEventId;
        }
        else
        {
            ArrayList<DBMSData> data = new ArrayList<DBMSData>();
            PlogLCR lastLCR = null;
            String lastProcessedEventId = null;

            LargeObjectScanner<PlogLCR> scanner = null;
            try
            {
                scanner = LCRList.scanner();
                while (scanner.hasNext())
                {
                    PlogLCR LCR = scanner.next();
                    if (LCR.type == PlogLCR.ETYPE_LCR_DATA)
                    { /* add only data */
                        if (LCR.subtype != PlogLCR.ESTYPE_LCR_DDL)
                        {
                            throw new ReplicatorException(
                                    "Internal corruption: DML statement in a DDL transaction.");
                        }
                        LCR.eventId = "" + commitSCN + "#" + XID + "#" + "LAST"
                                + "#" + minSCN + "#" + lastObsoletePlogSeq;
                        data.add(convertLCRtoDBMSDataDDL(LCR));
                        lastLCR = LCR;
                        lastProcessedEventId = LCR.eventId;
                        if (logger.isDebugEnabled())
                        {
                            logger.info("DDL: [" + LCR.currentSchema + "]["
                                    + LCR.eventId + "]: " + LCR.SQLText);
                        }
                    }
                    else
                    {
                        throw new RuntimeException("Type " + LCR.type
                                + " in queue, should be only data");
                    }
                }
            }
            catch (IOException e)
            {
                logger.error("Transaction scanner error occured: XID="
                        + this.XID + " key=" + LCRList.getKey() + " cache="
                        + LCRList.getByteCache().toString());
                throw new RuntimeException(
                        "Unable to read transaction from cache: " + this.XID);
            }
            finally
            {
                if (scanner != null)
                    scanner.close();
            }

            if (!data.isEmpty())
            {
                DBMSEvent event = new DBMSEvent(lastLCR.eventId, data, true,
                        commitTime);
                event.setMetaDataOption(ReplOptionParams.DBMS_TYPE,
                        Database.ORACLE);
                event.setMetaDataOption(ReplOptionParams.STRINGS, "utf8");
                q.put(event);
            }
            return lastProcessedEventId;
        }
    }

    /**
     * convert this (DDL) LCR to a DBMSData (StatementData) object
     * 
     * @param LCR LCR to process
     */
    private DBMSData convertLCRtoDBMSDataDDL(PlogLCR LCR)
    {
        LCR.parseDDLInfo();
        StatementData statement = new StatementData(LCR.SQLText, null,
                LCR.currentSchema);
        return statement;
    }

    /**
     * convert this (DML) LCR to a DBMSData (RowChangeData) object
     * 
     * @param LCR LCR to process
     */
    private DBMSData convertLCRtoDBMSDataDML(PlogLCR LCR)
            throws ReplicatorException, UnsupportedEncodingException,
            SerialException, SQLException
    {

        int indexKey = 1;

        RowChangeData rowData = new RowChangeData();

        OneRowChange oneRowChange = new OneRowChange(LCR.tableOwner,
                LCR.tableName, LCR.subtypeAsActionType());
        rowData.appendOneRowChange(oneRowChange);

        LCR.parseDataTypes(oneRowChange);

        ArrayList<ColumnSpec> keySpec = oneRowChange.getKeySpec();
        ArrayList<ColumnSpec> valSpec = oneRowChange.getColumnSpec();

        ArrayList<ArrayList<OneRowChange.ColumnVal>> keyValuesArray = oneRowChange
                .getKeyValues();
        ArrayList<OneRowChange.ColumnVal> keyValues = new ArrayList<ColumnVal>();
        keyValuesArray.add(keyValues);
        ArrayList<ArrayList<OneRowChange.ColumnVal>> valValuesArray = oneRowChange
                .getColumnValues();
        ArrayList<OneRowChange.ColumnVal> valValues = new ArrayList<ColumnVal>();
        valValuesArray.add(valValues);

        if (logger.isDebugEnabled())
        {
            logger.debug("Row Change: " + oneRowChange.getAction().toString()
                    + ":" + oneRowChange.getSchemaName() + "."
                    + oneRowChange.getTableName());
        }

        HashMap<String, Integer> columnsPresentAny = new HashMap<String, Integer>();
        HashMap<Integer, PlogLCR.oneColVal> columnsPresentKey = new HashMap<Integer, PlogLCR.oneColVal>();
        HashMap<Integer, PlogLCR.oneColVal> columnsPresentVal = new HashMap<Integer, PlogLCR.oneColVal>();

        for (PlogLCR.oneColVal oneCol : LCR.columnValues)
        {
            oneCol.parseValue();
            int thisKey;
            String p = "?";
            if (oneCol.imageType == PlogLCRTag.TAG_KEYIMAGE)
            {
                p = "KEY";
            }
            else if (oneCol.imageType == PlogLCRTag.TAG_PREIMAGE)
            {
                p = "PRE";
            }
            else if (oneCol.imageType == PlogLCRTag.TAG_POSTIMAGE)
            {
                p = "POST";
            }

            if (oneCol.imageType == PlogLCRTag.TAG_KEYIMAGE
                    || oneCol.imageType == PlogLCRTag.TAG_PREIMAGE)
            {
                if (columnsPresentAny.containsKey(oneCol.columnSpec.getName()))
                {
                    thisKey = columnsPresentAny
                            .get(oneCol.columnSpec.getName());
                }
                else
                {
                    thisKey = oneCol.id;// indexKey++;
                    indexKey = thisKey > indexKey ? thisKey : indexKey;
                    columnsPresentAny.put(oneCol.columnSpec.getName(), thisKey);
                }
                columnsPresentKey.put(thisKey, oneCol);
            }
            else
            { /* POST or LOB */
                if (columnsPresentAny.containsKey(oneCol.columnSpec.getName()))
                {
                    thisKey = columnsPresentAny
                            .get(oneCol.columnSpec.getName());
                }
                else
                {
                    thisKey = oneCol.id;// indexKey++;
                    indexKey = thisKey > indexKey ? thisKey : indexKey;
                    columnsPresentAny.put(oneCol.columnSpec.getName(), thisKey);
                }
                columnsPresentVal.put(thisKey, oneCol);
            }
            if (logger.isDebugEnabled())
            {
                logger.debug("Col [[" + p + "] #" + oneCol.id + "->" + thisKey
                        + " " + oneCol.name + "[" + oneCol.datatype + "] "
                        + oneCol.imageType);
            }
        }

        int idx;
        for (idx = 1; idx <= indexKey; idx++)
        {
            if (columnsPresentKey.containsKey(idx))
            {
                PlogLCR.oneColVal oneCol = columnsPresentKey.get(idx);
                oneCol.columnSpec.setIndex(idx);
                keySpec.add(oneCol.columnSpec);
                keyValues.add(oneCol.columnVal);
                if (logger.isDebugEnabled())
                {
                    logger.debug("Col [KEY] #" + oneCol.id + "->" + idx + " "
                            + oneCol.name + "[" + oneCol.datatype + "] "
                            + oneCol.imageType);
                }
            }
            if (columnsPresentVal.containsKey(idx))
            {
                PlogLCR.oneColVal oneCol = columnsPresentVal.get(idx);
                oneCol.columnSpec.setIndex(idx);
                valSpec.add(oneCol.columnSpec);
                valValues.add(oneCol.columnVal);
                if (logger.isDebugEnabled())
                {
                    logger.debug("Col [VAL] #" + oneCol.id + "->" + idx + " "
                            + oneCol.name + "[" + oneCol.datatype + "] "
                            + oneCol.imageType);
                }
            }
            else
                if (columnsPresentKey.containsKey(idx)
                        && oneRowChange.getAction() != ActionType.DELETE)
            { /* copy Key to Val */
                PlogLCR.oneColVal oneCol = columnsPresentKey.get(idx);
                valSpec.add(oneCol.columnSpec);
                valValues.add(oneCol.columnVal);
                if (logger.isDebugEnabled())
                {
                    logger.debug("Col [KEY->VAL]#" + oneCol.id + "->" + idx
                            + " " + oneCol.name + "[" + oneCol.datatype + "] "
                            + oneCol.imageType);
                }
            }
        }

        return rowData;
    }

}