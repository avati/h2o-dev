# Checks H2O connection and installs H2O R package matching version on server if indicated by user
# 1) If can't connect and user doesn't want to start H2O, stop immediately
# 2) If user does want to start H2O and running locally, attempt to bring up H2O launcher
# 3) If user does want to start H2O, but running non-locally, print an error
h2o.init <- function(ip = "127.0.0.1", port = 54321, startH2O = TRUE, forceDL = FALSE, Xmx = "1g", beta = FALSE, license = NULL) {
  if(!is.character(ip)) stop("ip must be of class character")
  if(!is.numeric(port)) stop("port must be of class numeric")
  if(!is.logical(startH2O)) stop("startH2O must be of class logical")
  if(!is.logical(forceDL)) stop("forceDL must be of class logical")
  if(!is.character(Xmx)) stop("Xmx must be of class character")
  if(!regexpr("^[1-9][0-9]*[gGmM]$", Xmx)) stop("Xmx option must be like 1g or 1024m")
  if(!is.logical(beta)) stop("beta must be of class logical")
  if(!is.null(license) && !is.character(license)) stop("license must be of class character")

  myURL = paste("http://", ip, ":", port, sep="")
  if(!url.exists(myURL)) {
    if(!startH2O)
      stop(paste("Cannot connect to H2O server. Please check that H2O is running at", myURL))
    else if(ip == "localhost" || ip == "127.0.0.1") {
      cat("\nH2O is not running yet, starting it now...\n")
      .h2o.startJar(memory = Xmx, beta = beta, forceDL = forceDL, license = license)
      count = 0; while(!url.exists(myURL) && count < 60) { Sys.sleep(1); count = count + 1 }
      if(!url.exists(myURL)) stop("H2O failed to start, stopping execution.")
    } else stop("Can only start H2O launcher if IP address is localhost.")
  }
  cat("Successfully connected to", myURL, "\n")
  H2Oserver = new("H2OClient", ip = ip, port = port)
  # Sys.sleep(0.5)    # Give cluster time to come up
  h2o.clusterInfo(H2Oserver); cat("\n")
  
  if((verH2O = .h2o.__version(H2Oserver)) != (verPkg = packageVersion("h2o")))
    stop("Version mismatch! H2O is running version ", verH2O, " but R package is version ", toString(verPkg), "\n")
  return(H2Oserver)
}

# Shuts down H2O instance running at given IP and port
h2o.shutdown <- function(client, prompt = TRUE) {
  if(class(client) != "H2OClient") stop("client must be of class H2OClient")
  if(!is.logical(prompt)) stop("prompt must be of class logical")
  
  myURL = paste("http://", client@ip, ":", client@port, sep="")
  if(!url.exists(myURL)) stop(paste("There is no H2O instance running at", myURL))
  
  if(prompt) {
    ans = readline(paste("Are you sure you want to shutdown the H2O instance running at", myURL, "(Y/N)? "))
    temp = substr(ans, 1, 1)
  } else temp = "y"
  
  if(temp == "Y" || temp == "y") {
    res = getURLContent(paste(myURL, .h2o.__PAGE_SHUTDOWN, sep="/"))
    res = fromJSON(res)
    if(!is.null(res$error))
      stop(paste("Unable to shutdown H2O. Server returned the following error:\n", res$error))
  }
  
  if((client@ip == "localhost" || client@ip == "127.0.0.1") && exists(".startedH2O") && .startedH2O) 
    .startedH2O <<- FALSE
}

# ----------------------- Diagnostics ----------------------- #
# **** TODO: This isn't really a cluster status... it's a node status check for the node we're connected to.
# This is possibly confusing because this can come back without warning,
# but if a user tries to do any remoteSend, they will get a "cloud sick warning"
# Suggest cribbing the code from Internal.R that checks cloud status (or just call it here?)

h2o.clusterStatus <- function(client) {
  if(missing(client) || class(client) != "H2OClient") stop("client must be a H2OClient object")
  myURL = paste("http://", client@ip, ":", client@port, "/", .h2o.__PAGE_CLOUD, sep = "")
  if(!url.exists(myURL)) stop("Cannot connect to H2O instance at ", myURL)
  res = fromJSON(postForm(myURL, style = "POST"))
  
  cat("Version:", res$version, "\n")
  cat("Cloud name:", res$cloud_name, "\n")
  cat("Node name:", res$node_name, "\n")
  cat("Cloud size:", res$cloud_size, "\n")
  if(res$locked) cat("Cloud is locked\n\n") else cat("Accepting new members\n\n")
  if(is.null(res$nodes) || length(res$nodes) == 0) stop("No nodes found!")
  
  # Calculate how many seconds ago we last contacted cloud
  cur_time <- Sys.time()
  for(i in 1:length(res$nodes)) {
    last_contact_sec = as.numeric(res$nodes[[i]]$last_contact)/1e3
    time_diff = cur_time - as.POSIXct(last_contact_sec, origin = "1970-01-01")
    res$nodes[[i]]$last_contact = as.numeric(time_diff)
  }
  cnames = c("name", "value_size_bytes", "free_mem_bytes", "max_mem_bytes", "free_disk_bytes", "max_disk_bytes", "num_cpus", "system_load", "rpcs", "last_contact")
  temp = data.frame(t(sapply(res$nodes, c)))
  return(temp[,cnames])
}

#---------------------------- H2O Jar Initialization -------------------------------#
.h2o.pkg.path <- NULL
.h2o.jar.env <- new.env()    # Dummy variable used to shutdown H2O when R exits

.onLoad <- function(lib, pkg) {
  .h2o.pkg.path <<- paste(lib, pkg, sep = .Platform$file.sep)
  
  # installing RCurl requires curl and curl-config, which is typically separately installed
  rcurl_package_is_installed = length(find.package("RCurl", quiet = TRUE)) > 0
  if(!rcurl_package_is_installed) {
    if(.Platform$OS.type == "unix") {
      # packageStartupMessage("Checking libcurl version...")
      curl_path <- Sys.which("curl-config")
      if(curl_path[[1]] == '' || system2(curl_path, args = "--version") != 0)
        stop("libcurl not found! Please install libcurl (version 7.14.0 or higher) from http://curl.haxx.se. On Linux systems, 
              you will often have to explicitly install libcurl-devel to have the header files and the libcurl library.")
    }
  }
}

.onAttach <- function(libname, pkgname) {
  msg = paste(
    "\n",
    "----------------------------------------------------------------------\n",
    "\n",
    "Your next step is to start H2O and get a connection object (named\n",
    "'localH2O', for example):\n",
    "    > localH2O = h2o.init()\n",
    "\n",
    "For H2O package documentation, first call init() and then ask for help:\n",
    "    > localH2O = h2o.init()\n",
    "    > ??h2o\n",
    "\n",
    "To stop H2O you must explicitly call shutdown (either from R, as shown\n",
    "here, or from the Web UI):\n",
    "    > h2o.shutdown(localH2O)\n",
    "\n",
    "After starting H2O, you can use the Web UI at http://localhost:54321\n",
    "For more information visit http://docs.0xdata.com\n",
    "\n",
    "----------------------------------------------------------------------\n",
    sep = "")
  packageStartupMessage(msg)
  
  # Shut down local H2O when user exits from R
  .startedH2O <<- FALSE
  reg.finalizer(.h2o.jar.env, function(e) {
    ip = "127.0.0.1"; port = 54321
    myURL = paste("http://", ip, ":", port, sep = "")
            
    # require(RCurl); require(rjson)
    if(exists(".startedH2O") && .startedH2O && url.exists(myURL))
      h2o.shutdown(new("H2OClient", ip=ip, port=port), FALSE)
  }, onexit = TRUE)
}

# .onDetach <- function(libpath) {
#   if(exists(".LastOriginal", mode = "function"))
#      assign(".Last", get(".LastOriginal"), envir = .GlobalEnv)
#   else if(exists(".Last", envir = .GlobalEnv))
#     rm(".Last", envir = .GlobalEnv)
# }

# .onUnload <- function(libpath) {
#   ip = "127.0.0.1"; port = 54321
#   myURL = paste("http://", ip, ":", port, sep = "")
#   
#   require(RCurl); require(rjson)
#   if(exists(".startedH2O") && .startedH2O && url.exists(myURL))
#     h2o.shutdown(new("H2OClient", ip=ip, port=port), FALSE)
# }

.h2o.startJar <- function(memory = "1g", beta = FALSE, forceDL = FALSE, license = NULL) {
  command <- .h2o.checkJava()

  if (! is.null(license)) {
    if (! file.exists(license)) {
      stop(paste("License file not found (", license, ")", sep=""))
    }
  }

  # Note: Logging to stdout and stderr in Windows only works for R version 3.0.2 or later!
  if(.Platform$OS.type == "windows") {
    default_path <- paste("C:", "TMP", sep = .Platform$file.sep)
    if(file.exists(default_path))
      tmp_path <- default_path
    else if(file.exists(paste("C:", "TEMP", sep = .Platform$file.sep)))
      tmp_path <- paste("C:", "TEMP", sep = .Platform$file.sep)
    else if(file.exists(Sys.getenv("APPDATA")))
      tmp_path <- Sys.getenv("APPDATA")
    else
      stop("Error: Cannot log Java output. Please create the directory ", default_path, ", ensure it is writable, and re-initialize H2O")
    
    usr <- gsub("[^A-Za-z0-9]", "_", Sys.getenv("USERNAME"))
    stdout <- paste(tmp_path, paste("h2o", usr, "started_from_r.out", sep="_"), sep = .Platform$file.sep)
    stderr <- paste(tmp_path, paste("h2o", usr, "started_from_r.err", sep="_"), sep = .Platform$file.sep)
  } else {
    usr <- gsub("[^A-Za-z0-9]", "_", Sys.getenv("USER"))
    stdout <- paste("/tmp/h2o", usr, "started_from_r.out", sep="_")
    stderr <- paste("/tmp/h2o", usr, "started_from_r.err", sep="_")
  }
  
  # jar_file <- paste(.h2o.pkg.path, "java", "h2o.jar", sep = .Platform$file.sep)
  jar_file <- .h2o.downloadJar(overwrite = forceDL)
  jar_file <- paste('"', jar_file, '"', sep = "")
  args <- c(paste("-Xms", memory, sep=""),
            paste("-Xmx", memory, sep=""),
            "-jar", jar_file,
            "-name", "H2O_started_from_R",
            "-ip", "127.0.0.1",
            "-port", "54321"
            )
  if(beta) args <- c(args, "-beta")
  if(!is.null(license)) args <- c(args, "-license", license)

  cat("\n")
  cat(        "Note:  In case of errors look at the following log files:\n")
  cat(sprintf("           %s\n", stdout))
  cat(sprintf("           %s\n", stderr))
  cat("\n")
  system2(command, c("-version"))
  cat("\n")
  rc = system2(command,
               args=args,
               stdout=stdout,
               stderr=stderr,
               wait=FALSE)
  if (rc != 0) {
    stop(sprintf("Failed to exec %s with return code=%s", jar_file, as.character(rc)))
  }
  .startedH2O <<- TRUE
}

# This function returns the path to the Java executable if it exists
# 1) Check for Java in user's PATH
# 2) Check for JAVA_HOME environment variable
# 3) If Windows, check standard install locations in Program Files folder. Warn if JRE found, but not JDK since H2O requires JDK to run.
# 4) When all fails, stop and prompt user to download JDK from Oracle website.
.h2o.checkJava <- function() {
  if(nchar(Sys.which("java")) > 0)
    return(Sys.which("java"))
  else if(nchar(Sys.getenv("JAVA_HOME")) > 0)
    return(paste(Sys.getenv("JAVA_HOME"), "bin", "java.exe", sep = .Platform$file.sep))
  else if(.Platform$OS.type == "windows") {
    # Note: Should we require the version (32/64-bit) of Java to be the same as the version of R?
    prog_folder <- c("Program Files", "Program Files (x86)")
    for(prog in prog_folder) {
      prog_path <- paste("C:", prog, "Java", sep = .Platform$file.sep)
      jdk_folder <- list.files(prog_path, pattern = "jdk")
      
      for(jdk in jdk_folder) {
        path <- paste(prog_path, jdk, "bin", "java.exe", sep = .Platform$file.sep)
        if(file.exists(path)) return(path)
      }
    }
    
    # Check for existence of JRE and warn user
    for(prog in prog_folder) {
      path <- paste("C:", prog, "Java", "jre7", "bin", "java.exe", sep = .Platform$file.sep)
      if(file.exists(path)) warning("Found JRE at ", path, " but H2O requires the JDK to run.")
    }
  }
  
  stop("Cannot find Java. Please install the latest JDK from http://www.oracle.com/technetwork/java/javase/downloads/index.html")
}

.h2o.downloadJar <- function(branch, version, overwrite = FALSE) {
  if(missing(branch)) branch <- packageDescription("h2o")$Branch
  if(missing(version)) version <- packageVersion("h2o")[1,4]
  if(!is.logical(overwrite)) stop("overwrite must be TRUE or FALSE")
  
  dest_folder <- paste(.h2o.pkg.path, "java", sep = .Platform$file.sep)
  if(!file.exists(dest_folder)) dir.create(dest_folder)
  dest_file <- paste(dest_folder, "h2o.jar", sep = .Platform$file.sep)
  
  # Download if h2o.jar doesn't already exist or user specifies force overwrite
  if(overwrite || !file.exists(dest_file)) {
    base_url <- paste("https://s3.amazonaws.com/h2o-release/h2o", branch, version, "Rjar", sep = "/")
    h2o_url <- paste(base_url, "h2o.jar", sep = "/")
    
    # Get MD5 checksum
    md5_url <- paste(base_url, "h2o.jar.md5", sep = "/")
    # ttt <- getURLContent(md5_url, binary = FALSE)
    # tcon <- textConnection(ttt)
    # md5_check <- readLines(tcon, n = 1)
    # close(tcon)
    md5_file <- tempfile(fileext = ".md5")
    download.file(md5_url, destfile = md5_file, mode = "w", cacheOK = FALSE, method = "curl", quiet = TRUE)
    md5_check <- readLines(md5_file, n = 1)
    if (nchar(md5_check) != 32) stop("md5 malformed, must be 32 characters (see ", md5_url, ")")
    unlink(md5_file)
    
    # Save to temporary file first to protect against incomplete downloads
    temp_file <- paste(dest_file, "tmp", sep = ".")
    cat("Performing one-time download of h2o.jar from\n")
    cat("    ", h2o_url, "\n")
    cat("(This could take a few minutes, please be patient...)\n")
    download.file(url = h2o_url, destfile = temp_file, mode = "wb", cacheOK = FALSE, method = "curl", quiet = TRUE)

    # Apply sanity checks
    if(!file.exists(temp_file))
      stop("Error: Transfer failed. Please download ", h2o_url, " and place h2o.jar in ", dest_folder)

    md5_temp_file = md5sum(temp_file)
    md5_temp_file_as_char = as.character(md5_temp_file)
    if(md5_temp_file_as_char != md5_check) {
      cat("Error: Expected MD5: ", md5_check, "\n")
      cat("Error: Actual MD5  : ", md5_temp_file_as_char, "\n")
      stop("Error: MD5 checksum of ", temp_file, " does not match ", md5_check)
    }

    # Move good file into final position
    file.rename(temp_file, dest_file)
  }
  return(dest_file)
}

#-------------------------------- Deprecated --------------------------------#
# NB: if H2OVersion matches \.99999$ is a development version, so pull package info out of file.  yes this is a hack
#     but it makes development versions properly prompt upgrade
# .h2o.checkPackage <- function(myURL, silentUpgrade, promptUpgrade) {
#   h2oWrapper.__formatError <- function(error, prefix="  ") {
#     result = ""
#     items = strsplit(error,"\n")[[1]];
#     for (i in 1:length(items))
#       result = paste(result, prefix, items[i], "\n", sep="")
#     result
#   }
#   
#   temp = postForm(paste(myURL, .h2o.__PAGE_RPACKAGE, sep="/"), style = "POST")
#   res = fromJSON(temp)
#   if (!is.null(res$error))
#     stop(paste(myURL," returned the following error:\n", h2oWrapper.__formatError(res$error)))
#   
#   H2OVersion = res$version
#   myFile = res$filename
#   
#   if( grepl('\\.99999$', H2OVersion) ){
#     H2OVersion <- sub('\\.tar\\.gz$', '', sub('.*_', '', myFile))
#   }
#   
#   # sigh. I so wish people would occasionally listen to me; R expects a version to be %d.%d.%d.%d and will ignore anything after
#   myPackages <- installed.packages()[,1]
#   needs_upgrade <- F
#   if( 'h2oRClient' %in% myPackages ){
#     ver <- unclass( packageVersion('h2oRClient') )
#     ver <- paste( ver[[1]], collapse='.' )
#     needs_upgrade <- !(ver == H2OVersion)
#   }
#   
#   if("h2oRClient" %in% myPackages && !needs_upgrade )
#     cat("H2O R package and server version", H2OVersion, "match\n")
#   else if(.h2o.shouldUpgrade(silentUpgrade, promptUpgrade, H2OVersion)) {
#     if("h2oRClient" %in% myPackages) {
#       cat("Removing old H2O R package version", toString(packageVersion("h2oRClient")), "\n")
#       if("package:h2oRClient" %in% search())
#         detach("package:h2oRClient", unload=TRUE)
#       remove.packages("h2oRClient")
#     }
#     cat("Downloading and installing H2O R package version", H2OVersion, "\n")
#     install.packages("h2oRClient", repos = c(H2O = paste(myURL, "R", sep = "/"), getOption("repos")))
#   }
# }
# 
# Check if user wants to install H2O R package matching version on server
# Note: silentUpgrade supercedes promptUpgrade
# .h2o.shouldUpgrade <- function(silentUpgrade, promptUpgrade, H2OVersion) {
#   if(silentUpgrade) return(TRUE)
#   if(promptUpgrade) {
#     ans = readline(paste("Do you want to install H2O R package version", H2OVersion, "from the server (Y/N)? "))
#     temp = substr(ans, 1, 1)
#     if(temp == "Y" || temp == "y") return(TRUE)
#     else if(temp == "N" || temp == "n") return(FALSE)
#     else stop("Invalid answer! Please enter Y for yes or N for no")
#   } else return(FALSE)
# }
