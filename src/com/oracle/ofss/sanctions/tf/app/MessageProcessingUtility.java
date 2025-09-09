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

public class MessageProcessingUtility {
    public MessageProcessingUtility() {
    }
    
    private static long labelledTime;
    private static String instanceBearerToken;
    private static final Object tokenLock = new Object();
    private static long bearerTokenRefreshInterval = 30L;
    private static String restartFlag = "N";
    private static int retryMaxCount = 5;
    private static String retryRequiredFlag = "Y";
    private static SimpleDateFormat sdf = new SimpleDateFormat(Constants.DATE_FORMAT);
    private static final AtomicInteger retryRequestNumber = new AtomicInteger(0);
    
    public static void screenRawMsg(String matchingEngine) throws Exception {
        long startTime = System.currentTimeMillis();

        System.out.println("=============================================================");
        System.out.println("                   MESSAGE POSTING STARTED                   ");
        System.out.println("=============================================================");
        Properties props = new Properties();

        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            throw e;
        }
        long maxIndex = getMaxIndex(props,"msgPosting.");
        System.out.println("Inside MessageProcessingUtility main method");
        if (maxIndex < 6) {
            System.out.println("Invalid arguments");
            System.out.println("Please send Url, filepath, tokenurl, Username and Password as arguments");
        } else {
            String tokenUrl = props.getProperty(Constants.TOKEN_URL);
            String usernm = props.getProperty(Constants.CLIENT_ID);
            String pwd = props.getProperty(Constants.CLIENT_SECRET);
            String devcorp7 = props.getProperty(Constants.DEVCORP7);
            String namespace = props.getProperty(Constants.NAMESPACE);
            String transactionService = props.getProperty(Constants.TRANSACTION_SERVICE).toLowerCase();
            String url = devcorp7+"/"+namespace+"/"+transactionService+Constants.POSTING_ENDPOINT;



//            System.out.println("tokenUrl: "+tokenUrl);
//            System.out.println("usernm: "+usernm);
//            System.out.println("pwd: "+pwd);
            System.out.println("url: "+url);


                if(maxIndex >= 10 ) {
            	retryRequiredFlag = props.getProperty(Constants.RETRY_REQUIRED_FLAG);
	            String retryMaxArg = props.getProperty(Constants.RETRY_MAX_COUNT);
	            String bearerTokenRefreshArg = props.getProperty(Constants.RETRY_REFRESH_INTERVAL);
	            String restartFlagArg = props.getProperty(Constants.RESTART_FLAG);
	            if(!retryMaxArg.isEmpty()) {
	            	retryMaxCount = Integer.parseInt(retryMaxArg);
	            }
	            if(!restartFlagArg.isEmpty()) {
	            	restartFlag = restartFlagArg;
	            }
	            if(!bearerTokenRefreshArg.isEmpty()) {
	            	bearerTokenRefreshInterval = Long.parseLong(bearerTokenRefreshArg);
	            }
	            System.out.println("UserDefinedParams:::retryRequiredFlag="+retryRequiredFlag+"; retryMaxCount="+retryMaxCount+"; bearerTokenRefreshInterval="+bearerTokenRefreshInterval+"min(s); restartFlag="+restartFlag);
            }




            try (FileInputStream fis = new FileInputStream(Constants.OUTPUT_XLSX_FILE_PATH);
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
                        matchingEngine+" "+Constants.FEEDBACK_STATUS
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

                System.out.println("["+sdf.format(new Date())+"] size of seqIdToRequestMap is " + seqIdToRequestMap.size());

                Map<String, String> failedRequestMap = processRequests(seqIdToRequestMap, tokenUrl, usernm, pwd, url, sheet, seqIdToRowNum, formatter, processorStartColumn);

                if (!failedRequestMap.isEmpty()) {
                    System.out.println("Job is not done yet...");
                    failedRequestMap = processRequests(failedRequestMap, tokenUrl, usernm, pwd, url, sheet, seqIdToRowNum, formatter, processorStartColumn);
                }

                // Auto-size new columns
                for (int i = processorStartColumn; i < processorStartColumn + processorHeaders.length; i++) {
                    sheet.autoSizeColumn(i);
                }

                FileOutputStream outFile = new FileOutputStream(Constants.OUTPUT_XLSX_FILE_PATH);
                workbook.write(outFile);
                outFile.close();

                System.out.println("["+sdf.format(new Date())+"] Message Processing Completed");

            } catch (Exception var36) {
                var36.printStackTrace();
                System.out.println("["+sdf.format(new Date())+"] Error occurred: " + var36.getMessage());
                System.exit(1);
            }

        }
        System.out.println("=============================================================");
        System.out.println("                   MESSAGE POSTING ENDED                     ");
        System.out.println("=============================================================");
        long endTime = System.currentTimeMillis();

        System.out.println("Time taken by Message Processor: " + (endTime - startTime) / 1000L + " seconds");

    }

    private static Map<String, String> processRequests(Map<String, String> seqIdToRequestMap, String tokenUrl, String usernm, String pwd, String url, Sheet sheet, Map<String, Integer> seqIdToRowNum, DataFormatter formatter, int processorStartColumn) {
        Map<String, String> failedRequestMap = new ConcurrentHashMap<>();

        seqIdToRequestMap.entrySet().parallelStream().forEach(entry -> {
            String seqId = entry.getKey();
            String requestBody = entry.getValue();
            long startTime = System.currentTimeMillis();
            int retryCount = 0;
            int responseCode = 500;
            StringBuilder apiResponse = new StringBuilder();
            BufferedReader br = null;

            System.out.println("["+sdf.format(new Date())+"] Executing REST call with SeqId: " + seqId);
            do {
                if (retryCount > 0) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("["+sdf.format(new Date())+"] Waiting for REST call to complete...");
                }
                int currentRetry = retryRequestNumber.incrementAndGet();

                String bearerToken;
                synchronized (tokenLock) {
                    bearerToken = getAccessToken(tokenUrl, usernm, pwd);
                }
                System.out.println("Access token: " + bearerToken);

                try {
                    URL resturl = new URL(url + "?reqId=" + currentRetry);
                    HttpsURLConnection conn = (HttpsURLConnection) resturl.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("ofs_remote_user", "OFS_SRV_ACCT");
                    conn.setRequestProperty("accept-language", "en-US,en-U");
                    conn.setRequestProperty("authorization", "Bearer " + bearerToken);
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

                    System.out.println("["+sdf.format(new Date())+"] Waiting for Response: " + getResponseMsg(responseCode));
                    System.out.println("api response::: "+ apiResponse);
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
            } while ("Y".equalsIgnoreCase(retryRequiredFlag) && responseCode > 399 && retryCount <= retryMaxCount);

            System.out.println("["+sdf.format(new Date())+"] ResponseCode: " + responseCode);

            long endTime = System.currentTimeMillis();
            System.out.println("=============================================================----------");
            System.out.println("Time taken for rest call: " + (endTime - startTime) / 1000L + " seconds");
            System.out.println("=============================================================----------");
            System.out.println("=============================================================----------------------------------------------");

            if (responseCode > 399) {
                failedRequestMap.put(seqId, requestBody);
            } else {
                JSONObject responseJson = new JSONObject(apiResponse.toString());
                System.out.println("response: " + responseJson);
                long transactionToken = responseJson.has(Constants.TRANSACTION_TOKEN) ? responseJson.getLong(Constants.TRANSACTION_TOKEN) : -1;
                long matchCount = responseJson.has(Constants.FEEDBACK_DATA) ? (responseJson.getJSONObject(Constants.FEEDBACK_DATA).has(Constants.MATCHING_COUNT) ? responseJson.getJSONObject(Constants.FEEDBACK_DATA).getLong(Constants.MATCHING_COUNT) : 0) : 0;
                String status = responseJson.optString(Constants.MATCHING_STATUS, "");
                String feedbackStatus = responseJson.has(Constants.FEEDBACK_DATA) ? responseJson.getJSONObject(Constants.FEEDBACK_DATA).optString(Constants.MATCHING_STATUS, "") : "";
                System.out.println("transactionToken: " + transactionToken + " matchCount: " + matchCount + " status: " + status + " feedbackStatus: " + feedbackStatus);
                Object[] excelParams = new Object[]{transactionToken, matchCount, status, feedbackStatus};

                // Update sheet in synchronized block
                synchronized (sheet) {
                    int targetRowNum = seqIdToRowNum.get(seqId);
                    System.out.println("Writing output to file for seqId: " + seqId);
                    Row row = (Row) sheet.getRow(targetRowNum);
//                    if (row == null) row = sheet.createRow(targetRowNum);
                    for (int i = 0; i < excelParams.length; i++) {
                        Cell cell = row.getCell(processorStartColumn + i);
                        if (cell == null) cell = row.createCell(processorStartColumn + i);
                        System.out.println(excelParams[i].toString());
                        cell.setCellValue(excelParams[i].toString());
                    }
                }
            }
            System.out.println("===========================================================================================================");
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
                  System.out.println("Writing output to file:" + seqId);
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
            System.out.println("--- Using cached bearerToken for " + (bearerTokenRefreshInterval - timeDiff) + " more min(s)");
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
                System.out.println("AuthToken response: " + response);
                JSONObject jsonObject = new JSONObject(response);
                bearerToken = jsonObject.getString("access_token");
                httpConn1.disconnect();
            }

            System.out.println("AuthToken status: " + status);
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
        case 502:
        	msg = Constants.WAIT_MSG;
        	break;
        case 504:
        	msg = Constants.HOLD_ON_MSG_1;
        	break;
        case 503:
        	msg = Constants.HOLD_ON_MSG_2;
        	break;
        case 200:
        	msg = Constants.SUCCESS_MSG;
        	break;
        default:
        	msg = Constants.LOAD_MSG;
        	break;
        }
        return msg;
    }
}
