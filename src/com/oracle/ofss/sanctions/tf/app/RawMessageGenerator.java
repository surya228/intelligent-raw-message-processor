package com.oracle.ofss.sanctions.tf.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.opencsv.CSVWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RawMessageGenerator {
    public static void generateRawMessage() throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("\n=============================================================");
        System.out.println("                RAW MESSAGE GENERATOR STARTED                ");
        System.out.println("=============================================================");
        Connection connection = null;
        JSONArray rawMessageJsonArray = null;
        ResultSet rs = null;


        try {
            String currentDir = System.getProperty("user.dir");
            File parentDir = new File(currentDir).getParentFile();
            String sourceFilePath = parentDir+File.separator+"bin"+File.separator+"source.json";
            String configFilePath = parentDir+File.separator+"bin"+File.separator+"config.properties";

            String srcFile = loadJsonFromFile(sourceFilePath);
            System.out.println("srcFile: "+srcFile);

            Properties props = new Properties();

            try (FileReader reader = new FileReader(configFilePath)) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("Error reading properties file: " + e.getMessage());
                throw e;
            }

            connection = getDbConnection();
            String tableName = Constants.TABLE_WL_MAP.get(props.getProperty("watchListType"));
            String tansactionService = props.getProperty("msgPosting.transactionService");
            rs = prepareQueryAndGetTableData(connection, props, tableName);

            rawMessageJsonArray = generateRawMessageJsonArray(rs,props,srcFile,tableName);

//            System.out.println(rawMessageJsonArray.toString(4).replace("<\\/", "</"));
//            writeJsonToFile(rawMessageJsonArray.toString(4).replace("\\r", "\r").replace("\\n", "\n").replace("<\\/", "</"));
//            writeJsonAsCSVFile(rawMessageJsonArray,tansactionService);
            writeJsonAsExcelFile(rawMessageJsonArray,tansactionService);

            System.out.println("\n=============================================================");
            System.out.println("                 RAW MESSAGE GENERATOR ENDED                 ");
            System.out.println("=============================================================");
            long endTime = System.currentTimeMillis();

            System.out.println("Time taken by Raw Message Generator: " + (endTime - startTime) / 1000L + " seconds");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (rs != null) {
                rs.close();
            }
            if (connection != null) {
                try {
                    connection.close();
                    System.out.println("Connection closed.");
                } catch (SQLException e) {
                    System.err.println("Failed to close the connection:");
                    e.printStackTrace();
                }
            }
        }
    }

    private static ResultSet prepareQueryAndGetTableData(Connection connection, Properties props, String tableName) throws Exception {
        PreparedStatement pst = null;
        ResultSet rs = null;
        String filter="";
        if(props.containsKey("whereClause")){
            filter = " where "+ props.get("whereClause");
        }

        String query = "select * from "+tableName+" "+filter;
        System.out.println("SQL Query generated:: "+query);
        try {
            pst = connection.prepareStatement(query);
            rs = pst.executeQuery();
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Something went wrong while preparing Query: ", e);
        }
        return rs;
    }

    private static JSONArray generateRawMessageJsonArray(ResultSet rs, Properties props, String srcFile, String tableName) throws Exception {
        JSONArray jsonArray = new JSONArray();
        int maxIndex = getMaxIndex(props, "replace.src");
        String temp;
        int updatedCount = 0;
        while(rs.next()) {
            temp=srcFile;
            if(temp != null) {
                for (int i = 1; i <= maxIndex; i++) {
                    String srcKey = "replace.src[" + i + "]";
                    String targetColumnKey = "replace.targetColumn[" + i + "]";

                    String token = props.getProperty(srcKey);
                    String targetColumn = props.getProperty(targetColumnKey);

                    String identifierToken =  props.getProperty("replace.src[0]");
                    String identifierTargetColumn = props.getProperty("replace.targetColumn[0]");

                    String toBeReplaced = rs.getString(targetColumn);
                    String identifierToBeReplaced = rs.getString(identifierTargetColumn);

                    temp = srcFile;
                    if (toBeReplaced != null) {
                        System.out.println("toBeReplaced: " + toBeReplaced + "  token: " + token + "  column: "+ targetColumn + "  identifier: "+ identifierToBeReplaced);
                        temp = temp.replace(token, toBeReplaced);
                        temp = temp.replace(identifierToken,identifierToBeReplaced);
//                            System.out.println("temp:" + temp);
                        JSONObject tempJson = new JSONObject(temp);
                        JSONObject additionalData = tempJson.getJSONObject("additionalData");
                        additionalData.put("table", tableName);
                        additionalData.put("column", targetColumn);
                        additionalData.put("token", token);
                        additionalData.put("value", toBeReplaced);
                        additionalData.put("identifierToken", identifierToken);
                        additionalData.put("identifierValue", identifierToBeReplaced);
                        jsonArray.put(tempJson);
                        updatedCount++;
                    }
                }
            }
        }
        System.out.println("No. of raw message created:: "+ updatedCount);
        return jsonArray;

    }

    private static int getMaxIndex(Properties props, String prefix) throws Exception {
        int maxIndex = 0;
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(prefix + "[")) {
                try {
                    int index = Integer.parseInt(key.substring(prefix.length() + 1, key.length() - 1));
                    maxIndex = Math.max(maxIndex, index);
                } catch (NumberFormatException e) {
                    throw new Exception("Something went wrong while getting maxIndex",e);
                }
            }
        }
        return maxIndex;
    }

    public static Connection getDbConnection() throws Exception {

        String currentDir = System.getProperty("user.dir");
        File parentDir = new File(currentDir).getParentFile();
        String configFilePath = parentDir+File.separator+"bin"+File.separator+"config.properties";
        Properties props = new Properties();
        try (FileReader reader = new FileReader(configFilePath)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            throw e;
        }

        String jdbcUrl = props.getProperty("jdbcurl");
        String jdbcDriver = props.getProperty("jdbcdriver");
        String username = props.getProperty("username");
        String password = props.getProperty("password");
        String walletname = props.getProperty("walletName");
        String tnsAdminPath = parentDir+File.separator+"bin"+File.separator+walletname;

        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.tns_admin", tnsAdminPath);
        Class.forName(jdbcDriver);
        Connection connection = DriverManager.getConnection(jdbcUrl,properties);
        System.out.println("Connection established successfully!");
        return connection;
    }

    public static String loadJsonFromFile(String filePath) {
        try {
            File file = new File(filePath);

            // Check if the file exists
            if (!file.exists()) {
                System.out.println("File not found: " + filePath);
                return null;
            }

            // Read the entire file content
            String jsonContent = Files.readString(Path.of(file.getPath()));

            // Parse the JSON data using JSONObject
            JSONObject jsonObject = new JSONObject(jsonContent);

            // Convert the parsed JSON back to a string
            return jsonObject.toString(4);
        } catch (Exception e) {
            System.out.println("An error occurred while reading the file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public static void writeJsonToFile(String content){
        String currentDir = System.getProperty("user.dir");
        File parentDir = new File(currentDir).getParentFile();
        // Create a subfolder "output" inside it
        File outputFolder = new File(parentDir, "out");
        if (!outputFolder.exists()) {
            outputFolder.mkdirs();  // Create the folder if it doesn't exist
        }
        try (FileWriter file = new FileWriter(outputFolder+File.separator+"output.json")) {
            file.write(content);
            System.out.println("Successfully wrote to file.");
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
        }
    }

//    public static Map<String, String> loadReplaceProps() throws IOException {
//
//        Properties props = new Properties();
//        try (FileReader reader = new FileReader("src/resources/config.properties")) {
//            props.load(reader);
//        } catch (IOException e) {
//            System.err.println("Error reading properties file: " + e.getMessage());
//            throw e;
//        }
//
//        Map<String, String> out = new HashMap<>();
//
//        for (String propName : props.stringPropertyNames()) {
//            if (propName.contains(".src[")) {
//                String src = props.getProperty(propName);
//                String targetColumnPropName = propName.replace(".src[", ".targetColumn[");
//                String targetColumn = props.getProperty(targetColumnPropName);
//                out.put(src, targetColumn);
//            }
//        }
//
//        return out;
//    }

//    public static Map<String, String> loadProps() throws IOException {
//
//        Properties props = new Properties();
//        try (FileReader reader = new FileReader("src/resources/config.properties")) {
//            props.load(reader);
//        } catch (IOException e) {
//            System.err.println("Error reading properties file: " + e.getMessage());
//            throw e;
//        }
//
//        Map<String, String> out = new HashMap<>();
//
//        for (String propName : props.stringPropertyNames()) {
//            if (!propName.contains("replace")) {
//                out.put(propName, props.getProperty(propName));
//            }
//        }
//
//        return out;
//    }

    public static void writeJsonAsCSVFile(JSONArray jsonArray, String tansactionService) throws IOException {
        String currentDir = System.getProperty("user.dir");
        File parentDir = new File(currentDir).getParentFile();
        // Create a subfolder "output" inside it
        File outputFolder = new File(parentDir, "out");
        if (!outputFolder.exists()) {
            outputFolder.mkdirs();  // Create the folder if it doesn't exist
        }
        try (CSVWriter writer = new CSVWriter(new FileWriter(outputFolder+File.separator+"output.csv"))) {
            // Write the header
            String thirdColumn = "Message "+tansactionService.toUpperCase();
            String[] headers = {"SeqNo", "Rule Name", thirdColumn, "Message Response"};
            writer.writeNext(headers);

            // Iterate over the JSON array and write each object as a row in the CSV file
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                String messageIso = jsonObject.toString(4).replace("\\r", "\r").replace("\\n", "\n").replace("~~~~", "\\\\").replace("<\\/", "</");
                String[] row = {String.valueOf(i + 1), "", messageIso, ""};
                writer.writeNext(row);
            }
        }
        System.out.println("Successfully wrote to CSV file.");
    }


    public static void writeJsonAsExcelFile(JSONArray jsonArray, String transactionService) throws IOException {
        String currentDir = System.getProperty("user.dir");
        File parentDir = new File(currentDir).getParentFile();

        // Create a subfolder "out" inside it
        File outputFolder = new File(parentDir, "out");
        if (!outputFolder.exists()) {
            outputFolder.mkdirs(); // Create the folder if it doesn't exist
        }

        // Create the Excel workbook and sheet
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Output");

        // Header row
        String thirdColumn = "Message " + transactionService.toUpperCase();
        String[] headers = {"SeqNo", "Rule Name", thirdColumn, "Transaction Token", "Match Count", "Status", "Feedback Status"};

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        // Write JSON data
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            String messageIso = jsonObject.toString(4)
                    .replace("\\r", "\r")
                    .replace("\\n", "\n")
                    .replace("~~~~", "\\\\")
                    .replace("<\\/", "</");

            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(i + 1);      // SeqNo
            row.createCell(1).setCellValue("");         // Rule Name
            row.createCell(2).setCellValue(messageIso); // Message <SERVICE>
            row.createCell(3).setCellValue("");         // Transaction Token
            row.createCell(4).setCellValue("");         // Match Count
            row.createCell(5).setCellValue("");         // Status
            row.createCell(6).setCellValue("");         // Feedback Status
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Write to file
        FileOutputStream fileOut = new FileOutputStream(new File(outputFolder, "output.xlsx"));
        workbook.write(fileOut);
        fileOut.close();
        workbook.close();

        System.out.println("Successfully wrote to Excel (output.xlsx) file.");
    }


    public static String replaceNewlinesInJsonStrings(String json) throws JsonProcessingException {

        Pattern pattern = Pattern.compile("\"rawMessage\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);

        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String rawValue = matcher.group(1);
            System.out.println("matched rawValue:"+rawValue);
            String escaped = rawValue
                    .replace("\\\\", "\\")
                    .replace("\\r", "\r")
                    .replace("\\n", "\n")
                    .replace("\\", "\\\\")   // escape existing backslashes
                    .replace("\r", "\\r")    // escape CR
                    .replace("\n", "\\n");   // escape LF
            matcher.appendReplacement(result, "\"rawMessage\": \"" + Matcher.quoteReplacement(escaped) + "\"");
        }
        matcher.appendTail(result);

        return result.toString();
    }
}
