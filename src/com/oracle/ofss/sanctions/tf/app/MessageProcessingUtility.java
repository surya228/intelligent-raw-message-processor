package com.oracle.ofss.sanctions.tf.app;

import java.io.*;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONObject;

public class MessageProcessingUtility {
    public MessageProcessingUtility() {
    }
    
    private static long labelledTime;
    private static String instanceBearerToken;
    private static long bearerTokenRefreshInterval = 30L;
    private static String restartFlag = "N";
    private static int retryMaxCount = 5;
    private static String retryRequiredFlag = "Y";
    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static int retryRequestNumber;
    
    public static void screenRawMsg() throws Exception {
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
            String tokenUrl = props.getProperty("msgPosting.tokenUrl");
            String usernm = props.getProperty("msgPosting.client.id");
            String pwd = props.getProperty("msgPosting.client.secret");
            String devcorp7 = props.getProperty("msgPosting.devcorp7");
            String namespace = props.getProperty("msgPosting.namespace");
            String transactionService = props.getProperty("msgPosting.transactionService").toLowerCase();
            String url = devcorp7+"/"+namespace+"/"+transactionService+"-transaction-service/sync/process";



//            System.out.println("tokenUrl: "+tokenUrl);
//            System.out.println("usernm: "+usernm);
//            System.out.println("pwd: "+pwd);
            System.out.println("url: "+url);


            if(maxIndex >= 10 ) {
            	retryRequiredFlag = props.getProperty("msgPosting.retryRequiredFlag");
	            String retryMaxArg = props.getProperty("msgPosting.retryMaxCount");
	            String bearerTokenRefreshArg = props.getProperty("msgPosting.bearerTokenRefreshInterval");
	            String restartFlagArg = props.getProperty("msgPosting.restartFlag");
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





            try {
                System.out.println("["+sdf.format(new Date())+"] Message Processing Started...");
                FileInputStream fis = new FileInputStream(Constants.OUTPUT_XLSX_FILE);
                XSSFWorkbook myWorkBook = new XSSFWorkbook(fis);
                XSSFSheet mySheet = myWorkBook.getSheetAt(0);
                Iterator<Row> rowIterator = mySheet.iterator();
                Map<String, String> seqIdToRequestMap = new LinkedHashMap<>();
                DataFormatter formatter = new DataFormatter();
                int rowNumber = 0;

                while(rowIterator.hasNext()) {
                    Row row = (Row)rowIterator.next();
                    if (rowNumber == 0) {
                        ++rowNumber;
                    } else if (row.getCell(0) != null && row.getCell(2) != null && !("Y".equalsIgnoreCase(restartFlag) && row.getCell(3)!=null)) {
                        seqIdToRequestMap.put(formatter.formatCellValue(row.getCell(0)), formatter.formatCellValue(row.getCell(2)));
                    }
                }
                myWorkBook.close();
                fis.close();

                System.out.println("["+sdf.format(new Date())+"] size of seqIdToRequestMap is " + seqIdToRequestMap.size());
                Map<String, String> failedRequestMap = new LinkedHashMap<>();
                retryRequestNumber = 0;

                processMapAndMakeRestCall(seqIdToRequestMap, tokenUrl, usernm, pwd, url, failedRequestMap);
                if(!failedRequestMap.isEmpty()) {
                	System.out.println("Job is not done yet...");
                	processMapAndMakeRestCall(failedRequestMap, tokenUrl, usernm, pwd, url, null);
                }

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

    private static long getMaxIndex(Properties props, String prefix) throws Exception {
        long count = props.keySet().stream()
                .map(Object::toString)
                .filter(key -> key.startsWith(prefix))
                .count();
        return count;
    }
    
    
    private static void processMapAndMakeRestCall(Map<String, String> seqIdToRequestMap, String tokenUrl, String usernm, String pwd, String url, Map<String, String> failedRequestMap) throws Exception {
    	
    	int requestNumber = 1;
    	DataFormatter formatter = new DataFormatter();
        BufferedReader br;
        String requestMethod;
        
        for(Iterator var17 = seqIdToRequestMap.entrySet().iterator(); var17.hasNext(); ++requestNumber) {
            Map.Entry<String, String> entry = (Map.Entry)var17.next();
            String bearerToken = getAccessToken(tokenUrl, usernm, pwd);
            long startTime = System.currentTimeMillis();
            System.out.println("Access token: " + bearerToken);
            requestMethod = "POST";
            int retryCount = 0;
            int responseCode;
            StringBuilder apiResponse;
            

            System.out.println("["+sdf.format(new Date())+"] Executing REST call......" + requestNumber + " with SeqId: "+entry.getKey());
            do {
            	if(retryCount > 0) {
            		Thread.sleep(5000);
            		System.out.println("["+sdf.format(new Date())+"] Waiting for REST call to get Complete...");
                }
            	retryRequestNumber++;

                URL resturl = new URL(url+"?reqId="+retryRequestNumber);
                HttpsURLConnection conn = (HttpsURLConnection)resturl.openConnection();
                conn.setRequestMethod(requestMethod);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("ofs_remote_user", "OFS_SRV_ACCT");
                conn.setRequestProperty("accept-language", "en-US,en-U");
                conn.setRequestProperty("authorization", "Bearer " + bearerToken);
                conn.setRequestProperty("idcs_remote_user", "appuser");
                conn.setRequestProperty("locale", "en-US");
                conn.setHostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String hostname, SSLSession sslSession) {
                        return true;
                    }
                });
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(entry.getValue().getBytes());
                os.flush();
                
                responseCode = conn.getResponseCode();
                if (100 <= responseCode && responseCode <= 399) {
                    br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                } else {
                    br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                }
                
                apiResponse = new StringBuilder();
                String output;
                while((output = br.readLine()) != null) {
                	apiResponse.append(output);
                }
                br.close();
                conn.disconnect();
                
                System.out.println("["+sdf.format(new Date())+"] Waiting for Response: " + getResponseMsg(responseCode));
                retryCount++;
                
            } while("Y".equalsIgnoreCase(retryRequiredFlag) && responseCode > 399 && retryCount<=retryMaxCount);
            
            
            
            System.out.println("["+sdf.format(new Date())+"] ResponseCode: " + responseCode);
            
            long endTime = System.currentTimeMillis();
            System.out.println("=============================================================----------");
            System.out.println("Time taken for rest call: " + (endTime - startTime) / 1000L + " seconds");
            System.out.println("=============================================================----------");
//            System.out.println("Response: " + apiResponse.toString());
            System.out.println("=============================================================----------------------------------------------");
            
            if(responseCode > 399 && failedRequestMap != null) {
            	failedRequestMap.put(entry.getKey(), entry.getValue());
            }
            
            JSONObject responseJson = new JSONObject(apiResponse.toString());
            System.out.println("response: "+ responseJson);
            long transactionToken = responseJson.has("transactionToken")? responseJson.getLong("transactionToken"):-1;
            long matchCount = responseJson.has("feedbackData")? responseJson.getJSONObject("feedbackData").has("matchCount")? responseJson.getJSONObject("feedbackData").getLong("matchCount"):0:0;
            String status = responseJson.getString("status");
            String feedbackStatus = responseJson.getJSONObject("feedbackData").getString("status");
            System.out.println("transactionToken: " + transactionToken + " matchCount: " + matchCount + " status: " + status + " feedbackStatus: " + feedbackStatus);
            Object[] excelParams = new Object[]{transactionToken,matchCount,status,feedbackStatus};
            writeRecordIntoExcelCell(entry.getKey(), excelParams, formatter);
            System.out.println("===========================================================================================================");
        }
        
    }
    
    private static void writeRecordIntoExcelCell(String processedSeqId, Object[] excelParams, DataFormatter formatter) {
    	try(FileInputStream fs = new FileInputStream(Constants.OUTPUT_XLSX_FILE);
                XSSFWorkbook workBook = new XSSFWorkbook(fs)) {
    		
            XSSFSheet newSheet = workBook.getSheetAt(0);
            Iterator<Row> rowItr = newSheet.iterator();
            int rowNum = 0;

            while(rowItr.hasNext()) {
                Row row = (Row)rowItr.next();
                if (rowNum == 0) {
                    ++rowNum;
                } else {
                    String seqId = formatter.formatCellValue(row.getCell(0));
                    if(processedSeqId.equalsIgnoreCase(seqId)) {
                        System.out.println("Writing output to file for seqId: " + seqId);
                        Cell cell;
                        int startWithCell=3;
                        for(int i=startWithCell;i<startWithCell+excelParams.length;i++){
                            if (row.getCell(i) == null) {
                                cell = row.createCell(i);
                            } else {
                                cell = row.getCell(i);
                            }
                            cell.setCellValue(excelParams[i-startWithCell].toString());
                        }
                        break;
                    }
                }
            }

            FileOutputStream outFile = new FileOutputStream(Constants.OUTPUT_XLSX_FILE);
            workBook.write(outFile);
            outFile.close();
        } catch (Exception e) {
        	e.printStackTrace();
        }
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
            result = postData.toString().getBytes("UTF-8");
        } catch (UnsupportedEncodingException var5) {
            var5.printStackTrace();
        }

        return result;
    }

    private static String encodeParam(String data) {
        String result = "";

        try {
            result = URLEncoder.encode(data, "UTF-8");
        } catch (UnsupportedEncodingException var3) {
            var3.printStackTrace();
        }

        return result;
    }

    private static String getAccessToken(String tokenUrl, String usernm, String pwd) {
        String bearerToken = "";
        long currentTime = System.currentTimeMillis();
        long timeDiff = (currentTime - labelledTime)/60000L ;
        if(labelledTime != 0L && (timeDiff < bearerTokenRefreshInterval)) {
        	System.out.println("--- Using cached bearerToken for "+(bearerTokenRefreshInterval-timeDiff)+" more min(s)");
        	return instanceBearerToken;
        }

        try {
            Map<String, String> headers = new HashMap();
            headers.put("grant_type", "client_credentials");
            headers.put("scope", "urn:opc:idm:__myscopes__");
            URL url1 = new URL(tokenUrl);
            HttpsURLConnection httpConn1 = (HttpsURLConnection)url1.openConnection();
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
                while((length = httpConn1.getInputStream().read(buffer)) != -1) {
                    result.write(buffer, 0, length);
                }

                response = result.toString("UTF-8");
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
        	msg = "Wait for a while...It's gonna finish";
        	break;
        case 504:
        	msg = "Hold on...It's almost completed";
        	break;
        case 503:
        	msg = "Looking for Tortoise...Will find it soon";
        	break;
        case 200:
        	msg = "Heyy...Here it is";
        	break;
        default:
        	msg = "zzzz...on the way";
        	break;
        }
        return msg;
    }
}