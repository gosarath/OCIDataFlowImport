import org.apache.spark.sql.SparkSession

object AdwSparkUpsert {
	def main(args: Array[String]) = {

import java.sql._
import java.util.Properties
import org.apache.spark.sql.execution.datasources.jdbc.JDBCOptions
import java.util.Properties
import oracle.jdbc.pool.OracleDataSource;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.driver.OracleDriver;
import java.io.File;
import java.io.FileOutputStream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import com.oracle.bmc.hdfs.BmcFilesystem;
import java.net.URI;
import org.apache.hadoop.fs.FileStatus;
import java.io.BufferedOutputStream;

	// args.foreach(arg => println(arg))

	// ADW username and password
	// val user = "xxxxx"
	// val pwd = "xxxxxxx"
	val user = args(0);
	val pwd = args(1)

        // Location of the wallet on the object store, it will be given as input for the application, unzip wallet contents here
        //val srcOciPathStr = new String("oci://dataflowadwwallet@bigdatadatasciencelarge/<walletFilename>");
        val srcOciPathStr = args(2)
	
        // Get the wallet directory name
        val walletName = srcOciPathStr.split("/").reverse(0)

	// ADW wallet and jdbc connection string, 
	val jdbcUrl = "jdbc:oracle:thin:@db202102142024_high?TNS_ADMIN=/tmp/" + walletName
	val jdbcDriver = "oracle.jdbc.driver.OracleDriver"
		
	// Populate Connection properties
	val connectionProperties = new Properties()
	connectionProperties.put("user",user)
	connectionProperties.put("password", pwd)
	connectionProperties.put("jdbcUrl", jdbcUrl)
		
	// Create or get Spark session
	var spark = SparkSession.builder()
		.appName("AdwSparkUpsert")
		.getOrCreate()
		
	// Read csv data file currently it has only 10 records on the customer tenancy
	//val dataFile = "oci://dataflowadwwallet@bigdatadatasciencelarge/SchoolsList.csv"
	val dataFile = args(3)
	val dfCSV = spark.read.option("header", "true").csv(dataFile)
	dfCSV.show(100, false) // display 10 records and not truncate the column headers

	// Get Spark Context
	val sc = spark.sparkContext

	//broadcast jdbc connection parameters to each partition so that they can instantiate JDBC connection locally
	val brConnect = sc.broadcast(connectionProperties)

	// Browse through each partition data, assuming lots of lots of data
	dfCSV.coalesce(100).foreachPartition(partition => {
		
        var destWalletPathStr = new String("/tmp/");
	val configuration = new Configuration();
		
	// Check to see if the /tmp already has the directory
	var destWalletPathDir = new File(destWalletPathStr+walletName);
        if (!destWalletPathDir.exists()) {
        	if (destWalletPathDir.mkdir()) {
            	println("Directory is created!");
            } else {
            	println("Failed to create directory!");
            }
	}

		// The following code is to copy wallet contents into the /tmp directory before we instantiate the jdbc connection 
		val config = new Configuration();

		config.set("fs.oci.client.auth.delegationTokenPath", "/var/run/secrets/oracle.dfcs/token");
		config.set("fs.oci.client.custom.client","oracle.dfcs.hdfs.DelegationObjectStorageClient");
		config.set("fs.oci.client.custom.authenticator","com.oracle.bmc.hdfs.auth.InstancePrincipalsCustomAuthenticator");
    		config.set("fs.oci.client.hostname","https://objectstorage.us-phoenix-1.oraclecloud.com");

		// We need to read it as file unlike spark data to be copied into /tmp destination
		val bmcFS = new BmcFilesystem()
		// val ociBucketUri = new String("oci://dataflowadwwallet@bigdatadatasciencelarge");
		//bmcFS.initialize(new URI(ociBucketUri),config)
		bmcFS.initialize(new URI(srcOciPathStr),config)
		var lstStatus = bmcFS.listStatus(new Path(srcOciPathStr));
		
		// iterate through the directory to get all the contents in the wallet directory
		for ( x <-  lstStatus ) {
			var fileSrcPath = x.getPath()
			var fileName = fileSrcPath.toString().split("/").reverse(0)

			val destPathFile = new Path(destWalletPathDir +"/"+ fileName)
			val srcPathFile = new Path(srcOciPathStr + fileName)			
			val srcFsStream = bmcFS.open(fileSrcPath);
			var fos = new FileOutputStream("/tmp/"+walletName+"/"+fileName);
			val buf = scala.Array.ofDim[Byte](1024)
	
			// Copy each file using buffered stream
			var length=srcFsStream.read(buf);
            		while (length > 0) {
                		fos.write(buf, 0, length);
				length = srcFsStream.read(buf);
            		}
     		}

		//Get the broadcasted connected parameters
		val connectionProperties = brConnect.value
			
		// Extract values from the broadcasted variables
		val jdbcURL = connectionProperties.getProperty("jdbcUrl")
		val user = connectionProperties.getProperty("user")
		val pwd = connectionProperties.getProperty("password")
			
		// Connect DataBase individually for each partition
		Class.forName(jdbcDriver)

		// Open the data base connection
		val dbc: Connection = DriverManager.getConnection(jdbcUrl, user, pwd)
		val db_batchsize = 100
		var st: PreparedStatement = null

		// Browse through each row in the partition
		partition.grouped(db_batchsize).foreach(batch => {
			batch.foreach { row => 
				{
				// get the column from dataframe row
				val schoolIDfldIndx = row.fieldIndex("schoolID")
				val schoolID = row.getString(schoolIDfldIndx)
				val schoolNamefldIndx = row.fieldIndex("schoolName")
				val schoolName = row.getString(schoolNamefldIndx)
				val schoolCityfldIndx = row.fieldIndex("schoolCity")
				val schoolCity = row.getString(schoolCityfldIndx)
				val schoolStatefldIndx = row.fieldIndex("schoolState")
				val schoolState = row.getString(schoolStatefldIndx)
				val schoolnickfldIndx = row.fieldIndex("schoolNickName")
				val schoolNickName = row.getString(schoolnickfldIndx)

				// Check the dataframe record exists
				val whereCol: List[String] = List("schoolID")
				val sqlString = "SELECT * from school where schoolID =?"
				var pstmt: PreparedStatement = dbc.prepareStatement(sqlString)
				pstmt.setString(1, schoolID)
				
				val rs = pstmt.executeQuery()
				var count: Int = 0
				while (rs.next()) { count = 1 }
					
				// Check whether it is UPDATE or INSERT operation
				var dmlOprtn = "NULL"
				var sqlUpsertString = "NULL"
				if (count > 0) {
					dmlOprtn = "UPDATE"
					sqlUpsertString = "UPDATE school SET schoolName=?, schoolCity=?,schoolState=?,schoolNickName=? WHERE schoolID=?"
					st = dbc.prepareStatement(sqlUpsertString)
				
					st.setString(1,schoolName)
					st.setString(2,schoolCity)
					st.setString(3,schoolState)
					st.setString(4,schoolNickName)
					st.setString(5,schoolID)	
				}
				else {
					dmlOprtn = "INSERT"
					sqlUpsertString = "INSERT INTO school(schoolID,schoolName,schoolCity,schoolState,schoolNickName) VALUES (?,?,?,?,?)"
					st = dbc.prepareStatement(sqlUpsertString)

					st.setString(1,schoolID)
					st.setString(2,schoolName)
					st.setString(3,schoolCity)
					st.setString(4,schoolState)
					st.setString(5,schoolNickName)
				}
				st.addBatch() //add the dataframe records to the batch update
				println(" schoolID =========> " + schoolID + "   =======   " + dmlOprtn)
				}
				st.executeBatch() //Execute the batched records
				}
			})

			// dbc.commit // Commit the records
			dbc.close // Close the Database connection for each partition
		})
	}
}
