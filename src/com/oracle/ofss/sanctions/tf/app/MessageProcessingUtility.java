package com.oracle.ofss.sanctions.tf.app;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.Base64;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONObject;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageProcessingUtility {
    private static final Logger logger = LoggerFactory.getLogger(MessageProcessingUtility.class);
    private static long labelledTime;
    private static String instanceBearerToken;
    private static final Object tokenLock = new Object();
    private static long bearerTokenRefreshInterval = Constants.DEFAULT_REFRESH_INTERVAL_MIN;
    private static String restartFlag = Constants.NO;
    private static int retryMaxCount = Constants.DEFAULT_RETRY_MAX;
    private static String retryRequiredFlag = Constants.YES;
    private static SimpleDateFormat sdf = new SimpleDateFormat(Constants.DATE_FORMAT);
    private static final AtomicInteger retryRequestNumber = new AtomicInteger(0);

    public static void screenRawMsg(String matchingEngine, File excelFile) throws Exception {
        screenRawMsg(matchingEngine, Collections.singletonList(excelFile));
    }

    public static void screenRawMsg(String matchingEngine, List<File> excelFiles) throws Exception {
        long startTime = System.currentTimeMillis();

        logger.info("=============================================================");
        logger.info("                   MESSAGE POSTING STARTED");
        logger.info("=============================================================");

        Properties props = loadProperties();
        long maxIndex = getMaxIndex(props, "msgPosting.");
        if (maxIndex < Constants.MIN_ARGS) {
            logger.info("Invalid arguments");
            logger.info("Please send Url, filepath, tokenurl, Username and Password as arguments");
            return;
        }

        String tokenUrl = props.getProperty(Constants.TOKEN_URL);
        String usernm = props.getProperty(Constants.CLIENT_ID);
        String pwd = props.getProperty(Constants.CLIENT_SECRET);
        String devcorp7 = props.getProperty(Constants.DEVCORP7);
        String namespace = props.getProperty(Constants.NAMESPACE);
        String transactionService = props.getProperty(Constants.TRANSACTION_SERVICE).toLowerCase();
        String url = devcorp7 + "/" + namespace + "/" + transactionService + Constants.POSTING_ENDPOINT;

        logger.info("url: " + url);

        String webServiceId = props.getProperty(Constants.WEBSERVICE_ID);
        String watchlistType = props.getProperty(Constants.WATCHLIST_TYPE);

        if (maxIndex >= 10) {
            configureRetryParameters(props);
            logger.info("UserDefinedParams:::retryRequiredFlag=" + retryRequiredFlag + "; retryMaxCount=" + retryMaxCount + "; bearerTokenRefreshInterval=" + bearerTokenRefreshInterval + "min(s); restartFlag=" + restartFlag);
        }

        if (excelFiles.isEmpty()) {
            logger.info("["+sdf.format(new Date())+"] No Excel files found to process.");
            return;
        }

        for (File excelFile : excelFiles) {
            logger.info("["+sdf.format(new Date())+"] Processing file: " + excelFile.getName());
            try (FileInputStream fis = new FileInputStream(excelFile);
                 Workbook workbook = new XSSFWorkbook(fis)) {
                Sheet sheet = workbook.getSheetAt(0);

                // Dynamically add processor columns
                Row headerRow = sheet.getRow(0);
                if (headerRow == null) headerRow = sheet.createRow(0);
                int lastColumn = headerRow.getLastCellNum();
                if (lastColumn < 0) lastColumn = 0;

                String[] processorHeaders = {
                        matchingEngine+" "+Constants.TRXN_TOKEN,
                        matchingEngine+" "+Constants.MATCH_COUNT,
                        matchingEngine+" "+Constants.STATUS,
                        matchingEngine+" "+Constants.FEEDBACK_STATUS,
                        matchingEngine+" # "+getMatchHeaderSuffix(webServiceId, watchlistType)+" matches",
                        matchingEngine+" Feedback"
                };
                int processorStartColumn = lastColumn;
                for (int i = 0; i < processorHeaders.length; i++) {
                    Cell headerCell = headerRow.getCell(processorStartColumn + i);
                    if (headerCell == null) headerCell = headerRow.createCell(processorStartColumn + i);
                    headerCell.setCellValue(processorHeaders[i]);
                }

                Iterator<Row> rowIterator = sheet.iterator();
                Map<String, String> seqIdToRequestMap = new LinkedHashMap<>();
                Map<String, Integer> seqIdToRowNum = new HashMap<>();
                DataFormatter formatter = new DataFormatter();
                int rowNumber = 0;

                while(rowIterator.hasNext()) {
                    Row row = (Row)rowIterator.next();
                    if (rowNumber == 0) {
                        ++rowNumber;
                        continue;
                    }
                    Cell seqCell = row.getCell(0);
                    Cell requestCell = row.getCell(2);
                    if (seqCell != null && requestCell != null) {
                        String seqId = formatter.formatCellValue(seqCell);
                        seqIdToRequestMap.put(seqId, formatter.formatCellValue(requestCell));
                        seqIdToRowNum.put(seqId, row.getRowNum());
                    }
                }

                logger.info("["+sdf.format(new Date())+"] size of seqIdToRequestMap in " + excelFile.getName() + " is " + seqIdToRequestMap.size());

                Map<String, String> failedRequestMap = processRequests(seqIdToRequestMap, tokenUrl, usernm, pwd, url, sheet, seqIdToRowNum, formatter, processorStartColumn, webServiceId, watchlistType);

                if (!failedRequestMap.isEmpty()) {
                    logger.info("Job is not done yet for " + excelFile.getName() + "...");
                    failedRequestMap = processRequests(failedRequestMap, tokenUrl, usernm, pwd, url, sheet, seqIdToRowNum, formatter, processorStartColumn, webServiceId, watchlistType);
                }

                // Auto-size new columns except the last (feedback) one
                for (int i = processorStartColumn; i < processorStartColumn + processorHeaders.length - 1; i++) {
                    sheet.autoSizeColumn(i);
                }

                try (FileOutputStream outFile = new FileOutputStream(excelFile)) {
                    workbook.write(outFile);
                }

                logger.info("["+sdf.format(new Date())+"] Message Processing Completed for " + excelFile.getName());

            } catch (Exception var36) {
                var36.printStackTrace();
                logger.info("["+sdf.format(new Date())+"] Error occurred processing " + excelFile.getName() + ": " + var36.getMessage());
                // Do not exit, continue with other files
            }
        }
        logger.info("=============================================================");
        logger.info("                   MESSAGE POSTING ENDED                     ");
        logger.info("=============================================================");
        long endTime = System.currentTimeMillis();

        logger.info("Time taken by Message Processor: " + (endTime - startTime) / 1000L + " seconds");

    }

    private static Properties loadProperties() throws IOException {
        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            logger.error("Error reading properties file: " + e.getMessage());
            throw e;
        }
        return props;
    }

    private static void configureRetryParameters(Properties props) {
        retryRequiredFlag = props.getProperty(Constants.RETRY_REQUIRED_FLAG);
        String retryMaxArg = props.getProperty(Constants.RETRY_MAX_COUNT);
        String bearerTokenRefreshArg = props.getProperty(Constants.RETRY_REFRESH_INTERVAL);
        String restartFlagArg = props.getProperty(Constants.RESTART_FLAG);
        if (!retryMaxArg.isEmpty()) {
            retryMaxCount = Integer.parseInt(retryMaxArg);
        }
        if (!restartFlagArg.isEmpty()) {
            restartFlag = restartFlagArg;
        }
        if (!bearerTokenRefreshArg.isEmpty()) {
            bearerTokenRefreshInterval = Long.parseLong(bearerTokenRefreshArg);
        }
    }

    private static Map<String, String> processRequests(Map<String, String> seqIdToRequestMap, String tokenUrl, String usernm, String pwd, String url, Sheet sheet, Map<String, Integer> seqIdToRowNum, DataFormatter formatter, int processorStartColumn, String webServiceId, String watchlistType) {
        Map<String, String> failedRequestMap = new ConcurrentHashMap<>();

        seqIdToRequestMap.entrySet().parallelStream().forEach(entry -> {
            String seqId = entry.getKey();
            String requestBody = entry.getValue();
            long startTime = System.currentTimeMillis();
            int retryCount = 0;
            int responseCode = 500;
            StringBuilder apiResponse = new StringBuilder();
            BufferedReader br = null;

            logger.info("["+sdf.format(new Date())+"] Executing REST call with SeqId: " + seqId);
            do {
                if (retryCount > 0) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    logger.info("["+sdf.format(new Date())+"] Waiting for REST call to complete...");
                }
                int currentRetry = retryRequestNumber.incrementAndGet();

                String bearerToken;
                synchronized (tokenLock) {
                    bearerToken = getAccessToken(tokenUrl, usernm, pwd);
                }
                logger.info("Access token: " + bearerToken);

                try {
                    URL resturl = new URL(url + "?reqId=" + currentRetry);
                    HttpsURLConnection conn = (HttpsURLConnection) resturl.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", Constants.CONTENT_TYPE_JSON);
                    conn.setRequestProperty("ofs_remote_user", "OFS_SRV_ACCT");
                    conn.setRequestProperty("accept-language", "en-US,en-U");
                    conn.setRequestProperty("authorization", Constants.AUTH_BEARER_PREFIX + bearerToken);
                    conn.setRequestProperty("idcs_remote_user", "appuser");
                    conn.setRequestProperty("locale", "en-US");
                    conn.setHostnameVerifier((hostname, sslSession) -> true);
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(requestBody.getBytes(Constants.ENCODER));
                        os.flush();
                    }

                    responseCode = conn.getResponseCode();
                    if (responseCode >= 100 && responseCode <= 399) {
                        br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    } else {
                        br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    }

                    apiResponse = new StringBuilder();
                    String output;
                    while ((output = br.readLine()) != null) {
                        apiResponse.append(output);
                    }
                    br.close();
                    conn.disconnect();

                    logger.info("["+sdf.format(new Date())+"] Waiting for Response: " + getResponseMsg(responseCode));
                    logger.info("api response::: "+ apiResponse);
                } catch (Exception e) {
                    e.printStackTrace();
                    responseCode = 500; // Treat as error for retry
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
                retryCount++;
            } while (Constants.YES.equalsIgnoreCase(retryRequiredFlag) && responseCode > 399 && retryCount <= retryMaxCount);

            logger.info("["+sdf.format(new Date())+"] ResponseCode: " + responseCode);

            long endTime = System.currentTimeMillis();
            logger.info("=============================================================----------");
            logger.info("Time taken for rest call: " + (endTime - startTime) / 1000L + " seconds");
            logger.info("=============================================================----------");
            logger.info("=============================================================----------------------------------------------");

            String responseString = apiResponse.toString();
            if (responseString.length() > 32767) {
                responseString = "value too large please check feedback api";
            }

            String tokenString = "NA";
            long matchCount = 0;
            String status = "NA";
            String feedbackStatus = "NA";
            long filteredCount = 0;

            boolean isErrorToHandle = (responseCode == 400 || responseCode == 500 || responseCode == Constants.SERVICE_UNAVAILABLE);

            if (responseCode <= 399 || isErrorToHandle) {
                // Try to parse JSON for transaction token
                try {
                    JSONObject responseJson = new JSONObject(apiResponse.toString());
                    if (responseJson.has(Constants.TRANSACTION_TOKEN)) {
                        long transactionToken = responseJson.getLong(Constants.TRANSACTION_TOKEN);
                        tokenString = String.valueOf(transactionToken);
                    }

                    if (responseCode <= 399) {
                        // Existing success logic
                        logger.info("response: " + responseJson);
                        matchCount = responseJson.has(Constants.FEEDBACK_DATA) ? (responseJson.getJSONObject(Constants.FEEDBACK_DATA).has(Constants.MATCHING_COUNT) ? responseJson.getJSONObject(Constants.FEEDBACK_DATA).getLong(Constants.MATCHING_COUNT) : 0) : 0;
                        status = responseJson.optString(Constants.MATCHING_STATUS, "");
                        feedbackStatus = responseJson.has(Constants.FEEDBACK_DATA) ? responseJson.getJSONObject(Constants.FEEDBACK_DATA).optString(Constants.MATCHING_STATUS, "") : "";
                        logger.info("transactionToken: " + tokenString + " matchCount: " + matchCount + " status: " + status + " feedbackStatus: " + feedbackStatus);

                        if (responseJson.has(Constants.FEEDBACK_DATA)) {
                            JSONObject feedbackData = responseJson.getJSONObject(Constants.FEEDBACK_DATA);
                            if (feedbackData.has(Constants.MATCHES)) {
                                JSONArray matches = feedbackData.getJSONArray(Constants.MATCHES);
                                for (int i = 0; i < matches.length(); i++) {
                                    JSONObject match = matches.getJSONObject(i);
                                    String matchWebServiceId = String.valueOf(match.optInt("webServiceID"));
                                    String matchWatchlistType = match.optString("watchlistType");
                                    if (matchWebServiceId.equals(webServiceId)
                                            && (!webServiceId.equals("3")
                                            && !webServiceId.equals("4")
                                            || matchWatchlistType.equalsIgnoreCase(watchlistType))) {
                                        filteredCount++;
                                    }
                                }
                            }
                        }
                    } else {
                        // Error handling for 400, 500, 503
                        status = "ERROR: " + responseCode;
                        // Other values remain default (0 or empty)
                    }

                    Object[] excelParams = new Object[]{tokenString, matchCount, status, feedbackStatus, filteredCount, responseString};

                    // Update sheet in synchronized block
                    synchronized (sheet) {
                        int targetRowNum = seqIdToRowNum.get(seqId);
                        logger.info("Writing output to file for seqId: " + seqId);
                        Row row = (Row) sheet.getRow(targetRowNum);
                        for (int i = 0; i < excelParams.length; i++) {
                            Cell cell = row.getCell(processorStartColumn + i);
                            if (cell == null) cell = row.createCell(processorStartColumn + i);
                            logger.info(excelParams[i].toString());
                            cell.setCellValue(excelParams[i].toString());
                        }
                    }
                } catch (Exception e) {
                    // If not valid JSON, use defaults and store raw response
                    status = (isErrorToHandle ? "ERROR: " + responseCode : "ERROR");
                    Object[] excelParams = new Object[]{tokenString, matchCount, status, feedbackStatus, filteredCount, responseString};

                    if (isErrorToHandle) {
                        synchronized (sheet) {
                            int targetRowNum = seqIdToRowNum.get(seqId);
                            logger.info("Writing output to file for seqId: " + seqId);
                            Row row = (Row) sheet.getRow(targetRowNum);
                            for (int i = 0; i < excelParams.length; i++) {
                                Cell cell = row.getCell(processorStartColumn + i);
                                if (cell == null) cell = row.createCell(processorStartColumn + i);
                                logger.info(excelParams[i].toString());
                                cell.setCellValue(excelParams[i].toString());
                            }
                        }
                    }
                }
            }

            if (responseCode > 399) {
                failedRequestMap.put(seqId, requestBody);
            }
            logger.info("===========================================================================================================");
        });

        return failedRequestMap;
    }

    private static long getMaxIndex(Properties props, String prefix) throws Exception {
        long count = props.keySet().stream()
                .map(Object::toString)
                .filter(key -> key.startsWith(prefix))
                .count();
        return count;
    }


    @SuppressWarnings("unused")
    private static void writeResponseMapIntoFile(File myFile, Map<String, String> seqIdToResponseMap, DataFormatter formatter) {
        try {
            FileInputStream fs = new FileInputStream(myFile);
            XSSFWorkbook workBook = new XSSFWorkbook(fs);
            XSSFSheet newSheet = workBook.getSheetAt(0);
            Iterator<Row> rowItr = newSheet.iterator();
            int rowNum = 0;

            while(rowItr.hasNext()) {
                Row row = (Row)rowItr.next();
                if (rowNum == 0) {
                    ++rowNum;
                } else {
                    String seqId = formatter.formatCellValue(row.getCell(0));
                    logger.info("Writing output to file:" + seqId);
                    if (seqIdToResponseMap.get(seqId) != null) {
                        Cell cell;
                        if (row.getCell(3) == null) {
                            cell = row.createCell(3);
                        } else {
                            cell = row.getCell(3);
                        }

                        if (((String)seqIdToResponseMap.get(seqId)).length() < 32000) {
                            cell.setCellValue((String)seqIdToResponseMap.get(seqId));
                        } else {
                            cell.setCellValue(((String)seqIdToResponseMap.get(seqId)).substring(0, 32000));
                        }
                    }
                }
            }

            fs.close();
            FileOutputStream outFile = new FileOutputStream(myFile);
            workBook.write(outFile);
            outFile.close();
            workBook.close();
        } catch (FileNotFoundException var34) {
            var34.printStackTrace();
        } catch (IOException var35) {
            var35.printStackTrace();
        }
    }


    private static byte[] getParamsByte(Map<String, String> params) {
        byte[] result = null;
        StringBuilder postData = new StringBuilder();
        Iterator var4 = params.entrySet().iterator();

        while(var4.hasNext()) {
            Map.Entry<String, String> param = (Map.Entry)var4.next();
            if (postData.length() != 0) {
                postData.append('&');
            }

            postData.append(encodeParam((String)param.getKey()));
            postData.append('=');
            postData.append(encodeParam(String.valueOf(param.getValue())));
        }

        try {
            result = postData.toString().getBytes(Constants.ENCODER);
        } catch (UnsupportedEncodingException var5) {
            var5.printStackTrace();
        }

        return result;
    }

    private static String encodeParam(String data) {
        String result = "";

        try {
            result = URLEncoder.encode(data, Constants.ENCODER);
        } catch (UnsupportedEncodingException var3) {
            var3.printStackTrace();
        }

        return result;
    }

    public static String getAccessToken(String tokenUrl, String usernm, String pwd) {
        long currentTime = System.currentTimeMillis();
        long timeDiff = (currentTime - labelledTime) / 60000L;
        if (labelledTime != 0L && timeDiff < bearerTokenRefreshInterval) {
            logger.info("--- Using cached bearerToken for " + (bearerTokenRefreshInterval - timeDiff) + " more min(s)");
            return instanceBearerToken;
        }

        String bearerToken = "";
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("grant_type", "client_credentials");
            headers.put("scope", "urn:opc:idm:__myscopes__");
            URL url1 = new URL(tokenUrl);
            HttpsURLConnection httpConn1 = (HttpsURLConnection) url1.openConnection();
            String userpass = usernm + ":" + pwd;
            String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userpass.getBytes()));
            httpConn1.setRequestMethod("POST");
            httpConn1.setDoOutput(true);
            httpConn1.setDoInput(true);
            httpConn1.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            httpConn1.setRequestProperty("Authorization", basicAuth);
            byte[] postDataBytes = getParamsByte(headers);
            httpConn1.getOutputStream().write(postDataBytes);
            int status = httpConn1.getResponseCode();
            if (status == 200) {
                String response = "";
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];

                int length;
                while ((length = httpConn1.getInputStream().read(buffer)) != -1) {
                    result.write(buffer, 0, length);
                }

                response = result.toString(Constants.ENCODER);
                logger.info("AuthToken response: " + response);
                JSONObject jsonObject = new JSONObject(response);
                bearerToken = jsonObject.getString("access_token");
                httpConn1.disconnect();
            }

            logger.info("AuthToken status: " + status);
        } catch (Exception var19) {
            var19.printStackTrace();
        }

        instanceBearerToken = bearerToken;
        labelledTime = System.currentTimeMillis();
        return bearerToken;
    }

    private static String getResponseMsg(int code) {
        String msg = "will do the needful";

        switch(code) {
            case Constants.BAD_GATEWAY:
                msg = Constants.WAIT_MSG;
                break;
            case Constants.GATEWAY_TIMEOUT:
                msg = Constants.HOLD_ON_MSG_1;
                break;
            case Constants.SERVICE_UNAVAILABLE:
                msg = Constants.HOLD_ON_MSG_2;
                break;
            case Constants.SUCCESS_CODE:
                msg = Constants.SUCCESS_MSG;
                break;
            default:
                msg = Constants.LOAD_MSG;
                break;
        }
        return msg;
    }

    private static String getMatchHeaderSuffix(String webServiceId, String watchlistType) {
        if (webServiceId.equals("3")) {
            if (watchlistType.equalsIgnoreCase("COUNTRY")) {
                return "Country";
            } else if (watchlistType.equalsIgnoreCase("CITY")) {
                return "City";
            } else {
                return "Country-City";
            }
        } else if (webServiceId.equals("4")) {
            if (watchlistType.equalsIgnoreCase("COUNTRY")) {
                return "Narrative Country";
            } else if (watchlistType.equalsIgnoreCase("CITY")) {
                return "Narrative City";
            } else if (watchlistType.equalsIgnoreCase("GOODS")) {
                return "Narrative Goods";
            } else if (watchlistType.equalsIgnoreCase("PORT")) {
                return "Narrative Port";
            } else if (watchlistType.equalsIgnoreCase("IDENTIFIER")) {
                return "Narrative Identifier";
            } else if (watchlistType.equalsIgnoreCase("STOP_KEYWORDS")) {
                return "Stopkeywords";
            } else {
                return "Narrative NameAndAddress";
            }
        } else {
            return Constants.WEBSERVICE_MAP.get(webServiceId);
        }
    }
}
