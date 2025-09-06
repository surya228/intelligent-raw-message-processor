package com.oracle.ofss.sanctions.tf.app;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Constants {
    public static final Map<String, String> TABLE_WL_MAP;
    static {
        Map<String, String> map = new HashMap<>();
        map.put("COUNTRY", "FCC_TF_DIM_COUNTRY");
        map.put("CITY", "FCC_TF_DIM_CITY");
        map.put("GOODS", "FCC_TF_DIM_GOODS");
        map.put("PORT", "FCC_TF_DIM_PORT");
        map.put("STOP_KEYWORDS", "FCC_TF_DIM_STOPKEYWORDS");
        map.put("IDENTIFIER", "FCC_DIM_IDENTIFIER");
        map.put("WCPREM", "FCC_WL_WC_PREMIUM");
        map.put("WCSTANDARD", "FCC_WL_WC_STANDARD");
        map.put("DJW", "FCC_WL_DJW");
        map.put("OFAC", "FCC_WL_OFAC");
        map.put("HMT", "FCC_WL_HMT");
        map.put("EU", "FCC_WL_EUROPEAN_UNION");
        map.put("UN", "FCC_WL_UN");
        map.put("PRV_WL1", "FCC_WL_PRIVATELIST");

        TABLE_WL_MAP = Collections.unmodifiableMap(map); // Make it read-only
    }

    public static String ENCODER = "UTF-8";

    public static String MODULE_RAW_MSG_GENERATOR =  "module.rawMsgGenerator";
    public static String MODULE_RAW_MSG_PROCESSOR =  "module.rawMsgProcessor";
    public static String MODULE_RAW_MSG_ANALYZER =  "module.rawMsgAnalyzer";


    public static String TAGNAME =  "tagName";
    public static String WEBSERVICE =  "webService";
    public static String EXACT =  "Exact";
    public static String FUZZY =  "Fuzzy - ";
    public static String WATCHLIST_TYPE =  "watchListType";
    public static String TRANSACTION_SERVICE =  "msgPosting.transactionService";
    public static String TOKEN_URL =  "msgPosting.tokenUrl";
    public static String CLIENT_ID =  "msgPosting.client.id";
    public static String CLIENT_SECRET =  "msgPosting.client.secret";
    public static String DEVCORP7 =  "msgPosting.devcorp7";
    public static String NAMESPACE =  "msgPosting.namespace";
    public static String RETRY_REQUIRED_FLAG =  "msgPosting.retryRequiredFlag";
    public static String RETRY_MAX_COUNT =  "msgPosting.retryMaxCount";
    public static String RETRY_REFRESH_INTERVAL =  "msgPosting.bearerTokenRefreshInterval";
    public static String RESTART_FLAG =  "msgPosting.restartFlag";
    public static String POSTING_ENDPOINT =  "-transaction-service/sync/process";
    public static String TRANSACTION_TOKEN =  "transactionToken";
    public static String FEEDBACK_DATA =  "feedbackData";
    public static String MATCHING_STATUS =  "status";
    public static String MATCHING_COUNT =  "matchCount";
    public static String WEBSERVICE_ID =  "webServiceId";


    public static String CED1 =  "ced1";
    public static String CED2 =  "ced2";
    public static String CED3 =  "ced3";
    public static String JDBC_URL =  "jdbcurl";
    public static String JDBC_DRIVER =  "jdbcdriver";
    public static String WALLET_NAME =  "walletName";
    public static String WHERE_CLAUSE =  "whereClause";
    public static String REPLACE_SRC =  "replace.src";
    public static String REPLACE_TARGET_COLUMN =  "replace.targetColumn";


    public static String ADDITIONAL_DATA =  "additionalData";
    public static String TABLE =  "table";
    public static String UID =  "uid";
    public static String COLUMN =  "column";
    public static String TOKEN =  "token";
    public static String VALUE =  "value";
    public static String ORIGINAL_VALUE =  "originalValue";
    public static String CED =  "ced";
    public static String IDEN_TOKEN =  "identifierToken";
    public static String IDEN_VALUE =  "identifierValue";

    public static String DATE_FORMAT =  "yyyy-MM-dd HH:mm:ss.SSS";

    public static String WAIT_MSG =  "Wait for a while...It's gonna finish";
    public static String HOLD_ON_MSG_1 =  "Hold on...It's almost completed";
    public static String HOLD_ON_MSG_2 =  "Looking for Tortoise...Will find it soon";
    public static String SUCCESS_MSG =  "Heyy...Here it is";
    public static String LOAD_MSG =  "zzzz...on the way";


    public static String SOURCE_FILE_NAME = "source";
    public static String CONFIG_FILE_NAME = "config";
    public static String OUTPUT_FILE_NAME = "output";

    public static String OUTPUT_FOLDER_NAME = "out";
    public static String BIN_FOLDER_NAME = "bin";

    public static String FEEDBACK_QUERY = "select C_FEEDBACK_MESSAGE from fcc_tf_feedback where N_TRAX_TOKEN = ? and V_MSG_CATEGORY = ? ";
    public static String WLS_RESPONSE_QUERY = "select N_RESPONSE_ID, V_COLUMN_NAME from fcc_tf_rt_wls_response where n_grp_msg_id = ? and n_msg_category = ? ";

    public static String CURRENT_DIRECTORY = System.getProperty("user.dir");
    public static File PARENT_DIRECTORY = new File(CURRENT_DIRECTORY).getParentFile();
    public static String SOURCE_FILE_PATH = PARENT_DIRECTORY+File.separator+Constants.BIN_FOLDER_NAME+File.separator+Constants.SOURCE_FILE_NAME+".json";
    public static String CONFIG_FILE_PATH = PARENT_DIRECTORY+File.separator+Constants.BIN_FOLDER_NAME+File.separator+Constants.CONFIG_FILE_NAME+".properties";
    public static File OUTPUT_FOLDER = new File(Constants.PARENT_DIRECTORY, Constants.OUTPUT_FOLDER_NAME);
    public static String OUTPUT_JSON_FILE_PATH = OUTPUT_FOLDER+File.separator+Constants.OUTPUT_FILE_NAME+".json";
    public static String OUTPUT_CSV_FILE_PATH = OUTPUT_FOLDER+File.separator+Constants.OUTPUT_FILE_NAME+".csv";
    public static File OUTPUT_XLSX_FILE_PATH = new File(OUTPUT_FOLDER,Constants.OUTPUT_FILE_NAME+".xlsx");

    public static String PASS = "PASS";
    public static String FAIL = "FAIL";

    public static String SEQ_NO = "SeqNo";
    public static String RULE = "Rule Name";
    public static String MESSAGE = "Message ";
    public static String TAG = "Tag";
    public static String SOURCE_INPUT = "Source Input";
    public static String TARGET_INPUT = "Target Input";
    public static String TARGET_COLUMN = "Target Column";
    public static String WATCHLIST = "Watchlist";
    public static String NUID = "N_UID";
    public static String TRXN_TOKEN = "Transaction Token";
    public static String MATCH_COUNT = "Match Count";
    public static String STATUS = "Status";
    public static String FEEDBACK_STATUS = "Feedback Status";
    public static String TEST_STATUS = "Test Status";

    public static int PROCESSOR_COLUMN_NUMBER = 9;
    public static int ANALYZER_COLUMN_NUMBER = 13;


}
