heading("BEGIN TEST")
conn <- new("H2OClient", ip=myIP, port=myPort)

heading("Uploading train data to H2O")
iris_train.hex <- h2o.uploadFile.FV(conn, train)

heading("Creating DRF model in H2O")
iris.rf.h2o <- h2o.randomForest.FV(x = x, y = y, data = iris_train.hex, ntree = ntree, depth = depth, nodesize = nodesize )
print(iris.rf.h2o)

heading("Downloading Java prediction model code from H2O")
model_key <- iris.rf.h2o@key
tmpdir_name <- sprintf("%s/results/tmp_model_%s", TEST_ROOT_DIR, as.character(Sys.getpid()))
cmd <- sprintf("rm -fr %s", tmpdir_name)
safeSystem(cmd)
cmd <- sprintf("mkdir -p %s", tmpdir_name)
safeSystem(cmd)
cmd <- sprintf("curl -o %s/%s.java http://%s:%d/2/DRFModelView.java?_modelKey=%s", tmpdir_name, model_key, myIP, myPort, model_key)
safeSystem(cmd)

heading("Uploading test data to H2O")
iris_test.hex <- h2o.uploadFile.FV(conn, test)

heading("Predicting in H2O")
iris.rf.pred <- h2o.predict(iris.rf.h2o, iris_test.hex)
summary(iris.rf.pred)
head(iris.rf.pred)
prediction1 <- as.data.frame(iris.rf.pred)
cmd <- sprintf("%s/out_h2o.csv", tmpdir_name)
write.csv(prediction1, cmd, quote=FALSE, row.names=FALSE)

heading("Setting up for Java POJO")
iris_test_with_response <- read.csv(test, header=T)
iris_test_without_response <- iris_test_with_response[,x]
write.csv(iris_test_without_response, file = sprintf("%s/in.csv", tmpdir_name), row.names=F, quote=F)
cmd <- sprintf("cp PredictCSV.java %s", tmpdir_name)
safeSystem(cmd)
cmd <- sprintf("javac -cp %s/h2o-model.jar -J-Xmx2g -J-XX:MaxPermSize=128m %s/PredictCSV.java %s/%s.java", H2O_JAR_DIR, tmpdir_name, tmpdir_name, model_key)
safeSystem(cmd)

heading("Predicting with Java POJO")
cmd <- sprintf("java -ea -cp %s/h2o-model.jar:%s -Xmx2g -XX:MaxPermSize=256m -XX:ReservedCodeCacheSize=256m PredictCSV --header --model %s --input %s/in.csv --output %s/out_pojo.csv", H2O_JAR_DIR, tmpdir_name, model_key, tmpdir_name, tmpdir_name)
safeSystem(cmd)

heading("Comparing predictions between H2O and Java POJO")
prediction2 <- read.csv(sprintf("%s/out_pojo.csv", tmpdir_name), header=T)
if (nrow(prediction1) != nrow(prediction2)) {
  warning("Prediction mismatch")
  print(paste("Rows from H2O", nrow(prediction1)))
  print(paste("Rows from Java POJO", nrow(prediction2)))
  stop("Number of rows mismatch")
}

match <- all(prediction1 == prediction2)
if (! match) {
  for (i in 1:nrow(prediction1)) {
    rowmatches <- all(prediction1[i,] == prediction2[i,])
    if (! rowmatches) {
      print("----------------------------------------------------------------------")
      print("")
      print(paste("Prediction mismatch on data row", i,    "of test file", test))
      print("")
      print(      "(Note: That is the 1-based data row number, not the file line number.")
      print(      "       If you have a header row, then the file line number is off by one.)")
      print("")
      print("----------------------------------------------------------------------")
      print("")
      print("Data from failing row")
      print("")
      print(iris_test_without_response[i,])
      print("")
      print("----------------------------------------------------------------------")
      print("")
      print("Prediction from H2O")
      print("")
      print(prediction1[i,])
      print("")
      print("----------------------------------------------------------------------")
      print("")
      print("Prediction from Java POJO")
      print("")
      print(prediction2[i,])
      print("")
      print("----------------------------------------------------------------------")
      print("")
      stop("Prediction mismatch")
    }
  }

  stop("Paranoid; should not reach here")
}

heading("Cleaning up tmp files")
cmd <- sprintf("rm -fr %s", tmpdir_name)
safeSystem(cmd)

PASS_BANNER()
