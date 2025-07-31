package com.oracle.ofss.sanctions.tf.app;

/**
 * Author: Vignesh Kanna P
 * Mail: vkvihari467@gmail.com
 * Date: 2025-03-23
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.opencsv.CSVWriter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Main {
    public static void main(String[] args) throws Exception {
        String walletPath = "src/resources/wallet_deviut3j16";
        String url = "jdbc:oracle:thin:@icpatomic?oracle.net.wallet_location=" + walletPath + "&TNS_ADMIN=" + walletPath;
        //String url = "jdbc:oracle:thin:@icpatomic?oracle.net.wallet_location=src/main/resources/tfcsdev121024-prd&TNS_ADMIN=src/main/resources/tfcsdev121024-prd";

        generateRawMessage(url);
//        MatchingRespJsonToExcelUtil.convertJsonToExcel(url);
    }

    public static void generateRawMessage(String url) throws Exception {
        Connection connection = null;
        PreparedStatement pst = null;
        ResultSet rs = null;
        JSONArray jsonArray = new JSONArray();

        try {
            String srcFile = loadJsonFromFile("src/resources/source.json");
            System.out.println("srcFile:"+srcFile);

            Properties props = new Properties();
            try (FileReader reader = new FileReader("src/resources/config.properties")) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("Error reading properties file: " + e.getMessage());
                throw e;
            }
            int maxIndex = getMaxIndex(props, "replace.src");

            connection = getDbConnection(url);
            String filter="";
            if(props.containsKey("whereClause")){
                filter = " where "+ props.get("whereClause");
            }
            String query = "select * from "+props.get("tableName")+" "+filter;
            System.out.println("SQL:: "+query);
            System.out.println("-------------------------------------------------------------");
            System.out.println("-------------------------------------------------------------");
            pst = connection.prepareStatement(query);

            rs = pst.executeQuery();
            String temp;

            while(rs.next()) {
                temp=srcFile;
                if(temp != null) {
                    for (int i = 1; i <= maxIndex; i++) {
                        String srcKey = "replace.src[" + i + "]";
                        String targetColumnKey = "replace.targetColumn[" + i + "]";

                        String src = props.getProperty(srcKey);
                        String targetColumn = props.getProperty(targetColumnKey);

                        String toBeReplaced = rs.getString(targetColumn);
                        temp = srcFile;
                        if (toBeReplaced != null) {
                            System.out.println("toBeReplaced: " + toBeReplaced + "  token: " + src + "  column: "+ targetColumn);
                            temp = temp.replace(src, toBeReplaced);
                            System.out.println("temp:" + temp);
                            jsonArray.put(new JSONObject(temp));
                        }
                    }
                }
            }

//            System.out.println(jsonArray.toString(4).replace("<\\/", "</"));
            writeJsonToFile(jsonArray.toString(4).replace("\\r", "\r").replace("\\n", "\n").replace("<\\/", "</"));
            writeJsonAsCSVFile(jsonArray);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (rs != null) {
                rs.close();
            }
            if (pst != null) {
                pst.close();
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

    public static Connection getDbConnection(String url) throws Exception {
        Class.forName("oracle.jdbc.driver.OracleDriver");
        Connection connection = DriverManager.getConnection(url);
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
        try (FileWriter file = new FileWriter("src/resources/output.json")) {
            file.write(content);
            System.out.println("Successfully wrote to file.");
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
        }
    }

    public static Map<String, String> loadReplaceProps() throws IOException {

        Properties props = new Properties();
        try (FileReader reader = new FileReader("src/resources/config.properties")) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            throw e;
        }

        Map<String, String> out = new HashMap<>();

        for (String propName : props.stringPropertyNames()) {
            if (propName.contains(".src[")) {
                String src = props.getProperty(propName);
                String targetColumnPropName = propName.replace(".src[", ".targetColumn[");
                String targetColumn = props.getProperty(targetColumnPropName);
                out.put(src, targetColumn);
            }
        }

        return out;
    }

    public static Map<String, String> loadProps() throws IOException {

        Properties props = new Properties();
        try (FileReader reader = new FileReader("src/resources/config.properties")) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            throw e;
        }

        Map<String, String> out = new HashMap<>();

        for (String propName : props.stringPropertyNames()) {
            if (!propName.contains("replace")) {
                out.put(propName, props.getProperty(propName));
            }
        }

        return out;
    }

    public static void writeJsonAsCSVFile(JSONArray jsonArray) throws IOException {

        try (CSVWriter writer = new CSVWriter(new FileWriter("src/resources/output.csv"))) {
            // Write the header
            String[] headers = {"SeqNo", "Rule_Name", "Message_ISO", "Message_Response"};
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
