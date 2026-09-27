import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as path from 'path';
import * as fs from 'fs';

export class HotelInfraStack extends cdk.Stack {
  public readonly bookingsTable: dynamodb.Table;
  public readonly apiLambda: lambda.Function;
  public readonly restApi: apigateway.LambdaRestApi;
  public readonly websiteBucket: s3.Bucket;
  public readonly distribution: cloudfront.Distribution;

  /** Returns why `jarPath` is unusable as the Lambda artifact, or undefined if it looks like a real JAR. */
  private static describeJarProblem(jarPath: string): string | undefined {
    if (!fs.existsSync(jarPath)) {
      return `the packaged backend JAR was not found at ${jarPath}`;
    }
    const stat = fs.statSync(jarPath);
    if (!stat.isFile() || stat.size === 0) {
      return `the packaged backend JAR at ${jarPath} is empty`;
    }
    // A JAR is a ZIP archive: it must start with the "PK" magic bytes.
    const fd = fs.openSync(jarPath, 'r');
    try {
      const magic = Buffer.alloc(2);
      fs.readSync(fd, magic, 0, 2, 0);
      if (magic.toString('latin1') !== 'PK') {
        return `${jarPath} is not a valid JAR/ZIP file`;
      }
    } finally {
      fs.closeSync(fd);
    }
    return undefined;
  }

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Guest-PII encryption (JWE) and booking-token signing (JWS) keys. These must be supplied at
    // deploy time — e.g.:
    //   HOTEL_ENCRYPTION_KEY_256=$(openssl rand -base64 32) \
    //   HOTEL_SIGNING_KEY_256=$(openssl rand -base64 48) \
    //   npx cdk deploy
    // Never hardcode real values here or commit them: this deliberately fails the synth/deploy
    // instead of silently falling back to the hardcoded development keys baked into the backend
    // JAR (see PiiEncryptionService / BookingTokenService), which would let anyone with the
    // source or the JAR decrypt guest PII and forge booking tokens.
    const encryptionKey = process.env.HOTEL_ENCRYPTION_KEY_256;
    const signingKey = process.env.HOTEL_SIGNING_KEY_256;

    // Optional, zero-cost UPI settings shown to guests as a payment QR (the guest pays this UPI ID
    // directly and enters the UTR; no payment gateway, no fees). Leave HOTEL_UPI_VPA unset to offer
    // "Pay at Hotel" only.
    const upiVpa = (process.env.HOTEL_UPI_VPA || '').trim();
    const upiPayeeName = (process.env.HOTEL_UPI_PAYEE_NAME || 'Hotel Atithi').trim();

    if (!encryptionKey || !signingKey) {
      throw new Error(
        'HOTEL_ENCRYPTION_KEY_256 and HOTEL_SIGNING_KEY_256 must both be set in the environment ' +
          'before running `cdk deploy` (e.g. `HOTEL_ENCRYPTION_KEY_256=$(openssl rand -base64 32) ' +
          'HOTEL_SIGNING_KEY_256=$(openssl rand -base64 48) npx cdk deploy`). Deploying without ' +
          'them would silently fall back to the hardcoded development keys checked into source.'
      );
    }

    // 1. DynamoDB Table - HotelBookings (Zero-Cost Free Tier: Provisioned 5 RCU / 5 WCU)
    this.bookingsTable = new dynamodb.Table(this, 'HotelBookingsTable', {
      tableName: 'HotelBookings',
      partitionKey: {
        name: 'PK',
        type: dynamodb.AttributeType.STRING,
      },
      sortKey: {
        name: 'SK',
        type: dynamodb.AttributeType.STRING,
      },
      billingMode: dynamodb.BillingMode.PROVISIONED,
      readCapacity: 5,
      writeCapacity: 5,
      timeToLiveAttribute: 'ttl',
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      pointInTimeRecoverySpecification: {
        pointInTimeRecoveryEnabled: false,
      }, // Keep PITR disabled to ensure strictly zero recurring cost
    });

    // Global Secondary Index: GSI1 (GSI1PK, GSI1SK) for date-range and customer queries
    this.bookingsTable.addGlobalSecondaryIndex({
      indexName: 'GSI1',
      partitionKey: {
        name: 'GSI1PK',
        type: dynamodb.AttributeType.STRING,
      },
      sortKey: {
        name: 'GSI1SK',
        type: dynamodb.AttributeType.STRING,
      },
      projectionType: dynamodb.ProjectionType.ALL,
      readCapacity: 5,
      writeCapacity: 5,
    });

    // Global Secondary Index: GSI2 (by date) — one partition per calendar date, one item per room.
    // Availability search reads a night's lock state for all 32 rooms with a single Query instead of
    // 32 per-room Queries. 5 RCU/WCU keeps total provisioned capacity (table + GSI1 + GSI2 = 15) well
    // inside the 25 RCU/WCU Free Tier allowance.
    this.bookingsTable.addGlobalSecondaryIndex({
      indexName: 'GSI2',
      partitionKey: {
        name: 'GSI2PK',
        type: dynamodb.AttributeType.STRING,
      },
      sortKey: {
        name: 'GSI2SK',
        type: dynamodb.AttributeType.STRING,
      },
      projectionType: dynamodb.ProjectionType.ALL,
      readCapacity: 5,
      writeCapacity: 5,
    });

    // Static Website Hosting (Zero-Cost Free Tier: S3 + CloudFront). Created BEFORE the Lambda and API so
    // the CloudFront domain can be handed to both as the one browser origin allowed to call the API.
    this.websiteBucket = new s3.Bucket(this, 'HotelWebsiteBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });

    // Security headers on the SPA itself (no charge): HSTS, nosniff, no framing, no referrer leakage.
    const siteSecurityHeaders = new cloudfront.ResponseHeadersPolicy(this, 'HotelSiteSecurityHeaders', {
      comment: 'Security headers for the Hotel Atithi website',
      securityHeadersBehavior: {
        strictTransportSecurity: {
          accessControlMaxAge: cdk.Duration.days(365),
          includeSubdomains: true,
          override: true,
        },
        contentTypeOptions: { override: true },
        frameOptions: { frameOption: cloudfront.HeadersFrameOption.DENY, override: true },
        referrerPolicy: {
          referrerPolicy: cloudfront.HeadersReferrerPolicy.NO_REFERRER,
          override: true,
        },
      },
    });

    this.distribution = new cloudfront.Distribution(this, 'HotelWebsiteDistribution', {
      comment: 'CloudFront distribution for 32-room hotel booking website',
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(this.websiteBucket),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        allowedMethods: cloudfront.AllowedMethods.ALLOW_GET_HEAD,
        cachedMethods: cloudfront.CachedMethods.CACHE_GET_HEAD,
        responseHeadersPolicy: siteSecurityHeaders,
      },
      defaultRootObject: 'index.html',
      errorResponses: [
        {
          httpStatus: 403,
          responseHttpStatus: 200,
          responsePagePath: '/index.html',
          ttl: cdk.Duration.seconds(0),
        },
        {
          httpStatus: 404,
          responseHttpStatus: 200,
          responsePagePath: '/index.html',
          ttl: cdk.Duration.seconds(0),
        },
      ],
      priceClass: cloudfront.PriceClass.PRICE_CLASS_100, // Lowest cost tier (includes India / Asia / US / Europe)
    });

    // Browser origins allowed to call the API (CORS): the CloudFront site plus the local Vite dev server (ports 5173 and 3000).
    // Add a custom domain with HOTEL_EXTRA_ALLOWED_ORIGINS="https://www.example.com" (comma-separated).
    // Never "*": the API is meant to be called only from our own site.
    const extraOrigins = (process.env.HOTEL_EXTRA_ALLOWED_ORIGINS || '')
      .split(',')
      .map((o) => o.trim().replace(/\/+$/, ''))
      .filter((o) => o.length > 0 && o !== '*');
    const allowedOrigins = [
      `https://${this.distribution.distributionDomainName}`,
      'http://localhost:5173', // Vite default dev port
      'http://localhost:3000', // port set in frontend/vite.config.ts
      ...extraOrigins,
    ];

    // Optional secret pepper for the guest-phone blind index (HMAC-SHA256). If unset, the backend derives
    // one from HOTEL_ENCRYPTION_KEY_256. Set it to decouple the index key from the encryption key:
    //   HOTEL_HMAC_PEPPER=$(openssl rand -base64 32)   (at least 16 characters)
    const hmacPepper = (process.env.HOTEL_HMAC_PEPPER || '').trim();

    // Phase 4 - front-desk console. HOTEL_STAFF_PIN is the shared PIN for /staff (zero-cost auth, no
    // third-party service). Unset = the backend falls back to the DEFAULT PIN 1439, which is only fine for
    // a first test: set a private PIN (6+ digits) before real use.
    const staffPin = (process.env.HOTEL_STAFF_PIN || '').trim();
    if (!staffPin) {
      cdk.Annotations.of(this).addWarning(
        'HOTEL_STAFF_PIN is not set: the staff console will accept the default PIN 1439. Set HOTEL_STAFF_PIN before deploying for real guests.');
    }

    // Phase 4 - optional confirmation e-mails through AWS SES (free tier). Set AWS_SES_SENDER_EMAIL to an
    // address/domain verified in SES (ap-south-1) to turn them on; unset = e-mails are only logged.
    const sesSenderEmail = (process.env.AWS_SES_SENDER_EMAIL || '').trim();

    // 2. Lambda Backend - Java 21 Runtime (Serverless Spring Boot 3)
    //
    // The Lambda code MUST be the shaded backend JAR. Previously a missing JAR silently fell back to
    // ../placeholder (a text file), so `cdk deploy` "succeeded" and shipped a Lambda that could never
    // start. Now a missing / empty / non-JAR artifact fails synth with an explicit message. Passing
    // `--context allowPlaceholder=true` opts in to the placeholder for a deliberate first-time
    // bootstrap of the stack (e.g. to create the table and buckets before the backend is built).
    const backendJar = path.join(__dirname, '../../backend/target/hotel-backend-1.0.0.jar');
    const allowPlaceholder = ['true', '1'].includes(String(this.node.tryGetContext('allowPlaceholder')));

    let assetPath: string;
    const jarProblem = HotelInfraStack.describeJarProblem(backendJar);
    if (jarProblem === undefined) {
      assetPath = backendJar;
    } else if (allowPlaceholder) {
      assetPath = path.join(__dirname, '../placeholder');
      if (!fs.existsSync(assetPath)) {
        fs.mkdirSync(assetPath, { recursive: true });
        fs.writeFileSync(path.join(assetPath, 'placeholder.txt'), 'hotel-backend-placeholder');
      }
      cdk.Annotations.of(this).addWarning(
        `Deploying a PLACEHOLDER Lambda (allowPlaceholder=true): ${jarProblem}. ` +
          'The API will not work until you build the backend and redeploy.'
      );
    } else {
      throw new Error(
        `Cannot deploy the API Lambda: ${jarProblem}\n` +
          'Build the backend first:  cd backend && ./mvnw clean package\n' +
          '(or, only for a deliberate bootstrap deploy, pass  --context allowPlaceholder=true )'
      );
    }

    this.apiLambda = new lambda.Function(this, 'HotelApiLambda', {
      functionName: 'HotelBookingApiFunction',
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.hotel.StreamLambdaHandler::handleRequest',
      code: lambda.Code.fromAsset(assetPath),
      memorySize: 1024,
      timeout: cdk.Duration.seconds(30),
      environment: {
        TABLE_NAME: this.bookingsTable.tableName,
        SPRING_PROFILES_ACTIVE: 'lambda',
        POWERTOOLS_SERVICE_NAME: 'hotel-booking-service',
        HOTEL_ENCRYPTION_KEY_256: encryptionKey,
        HOTEL_SIGNING_KEY_256: signingKey,
        HOTEL_UPI_VPA: upiVpa,
        HOTEL_UPI_PAYEE_NAME: upiPayeeName,
        HOTEL_ALLOWED_ORIGINS: allowedOrigins.join(','),
        HOTEL_HMAC_PEPPER: hmacPepper,
        HOTEL_STAFF_PIN: staffPin,
        AWS_SES_SENDER_EMAIL: sesSenderEmail,
      },
    });

    // SES send permission is granted only when e-mail is switched on (least privilege, still $0).
    if (sesSenderEmail) {
      this.apiLambda.addToRolePolicy(new iam.PolicyStatement({
        actions: ['ses:SendEmail'],
        resources: [`arn:aws:ses:${cdk.Stack.of(this).region}:${cdk.Stack.of(this).account}:identity/*`],
      }));
    }

    // Grant least-privilege read/write permissions to DynamoDB
    this.bookingsTable.grantReadWriteData(this.apiLambda);

    // 3. API Gateway REST API with CORS restricted to the allowed origins above
    this.restApi = new apigateway.LambdaRestApi(this, 'HotelRestApi', {
      restApiName: 'Hotel Booking Service API',
      description: 'REST API for 32-room Hotel Booking platform',
      handler: this.apiLambda,
      proxy: true,
      defaultCorsPreflightOptions: {
        allowOrigins: allowedOrigins,
        allowMethods: ['GET', 'POST', 'DELETE', 'OPTIONS'],
        allowHeaders: [
          'Content-Type',
          'Accept',
          // Sent by the SPA on confirm and release-hold; without it the browser's CORS preflight fails.
          'X-Booking-Token',
          // Phase 4: staff console session token (or Authorization: Bearer <token>).
          'X-Staff-Token',
          'Authorization',
        ],
        maxAge: cdk.Duration.hours(1),
      },
      deployOptions: {
        stageName: 'prod',
        throttlingRateLimit: 50,
        throttlingBurstLimit: 100,
      },
    });

    // Errors produced by API Gateway itself (throttling 429, 4xx/5xx before Lambda) never reach Spring, so
    // give them the same security headers; CORS is added too so the SPA can read a gateway 429 instead of
    // seeing an opaque network error. The Lambda's own responses get their headers from Spring filters.
    const gatewayErrorHeaders: { [name: string]: string } = {
      'gatewayresponse.header.X-Content-Type-Options': "'nosniff'",
      'gatewayresponse.header.X-Frame-Options': "'DENY'",
      'gatewayresponse.header.Strict-Transport-Security': "'max-age=31536000; includeSubDomains'",
      'gatewayresponse.header.Access-Control-Allow-Origin': `'${allowedOrigins[0]}'`,
    };
    this.restApi.addGatewayResponse('HotelGatewayDefault4xx', {
      type: apigateway.ResponseType.DEFAULT_4XX,
      responseHeaders: gatewayErrorHeaders,
    });
    this.restApi.addGatewayResponse('HotelGatewayDefault5xx', {
      type: apigateway.ResponseType.DEFAULT_5XX,
      responseHeaders: gatewayErrorHeaders,
    });

    // Stack Outputs
    new cdk.CfnOutput(this, 'DynamoDBTableName', {
      value: this.bookingsTable.tableName,
      description: 'DynamoDB Table Name',
      exportName: 'HotelBookingsTableName',
    });

    new cdk.CfnOutput(this, 'ApiGatewayEndpoint', {
      value: this.restApi.url,
      description: 'Public REST API Gateway URL',
      exportName: 'HotelApiEndpoint',
    });

    new cdk.CfnOutput(this, 'LambdaFunctionArn', {
      value: this.apiLambda.functionArn,
      description: 'Java 21 Lambda Function ARN',
      exportName: 'HotelLambdaArn',
    });

    new cdk.CfnOutput(this, 'WebsiteURL', {
      value: `https://${this.distribution.distributionDomainName}`,
      description: 'Public CloudFront Website URL',
      exportName: 'HotelWebsiteURL',
    });

    new cdk.CfnOutput(this, 'WebsiteBucketName', {
      value: this.websiteBucket.bucketName,
      description: 'S3 Website Bucket Name',
      exportName: 'HotelWebsiteBucketName',
    });
  }
}
