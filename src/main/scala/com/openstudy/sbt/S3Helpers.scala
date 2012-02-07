package com.openstudy { package sbt {
  import java.io._

  import org.jets3t.service._
    import acl._
    import impl.rest.httpclient._
    import model._
    import security._
    import utils._

  trait S3 {
    def accessKey : String
    def secretKey : String

    lazy val credentials =
      new AWSCredentials(accessKey, secretKey)
    lazy val s3 = new RestS3Service(credentials)
  }
  trait S3Handler extends S3 {
    def bucket : String

    lazy val bucketAcl = {
      val acl = s3.getBucketAcl(bucket);
      acl.grantPermission(GroupGrantee.ALL_USERS, Permission.PERMISSION_READ);

      acl
    }

    def saveFile(mime:String, fileName:String, data:Array[Byte]) = {
      val s3Object = new S3Object(fileName, data)
      s3Object.setContentType(mime)
      s3Object.setAcl(bucketAcl)

      s3.putObject(bucket, s3Object)
    }
  }
} }
