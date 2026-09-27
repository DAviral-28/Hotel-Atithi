#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { HotelInfraStack } from '../lib/hotel-infra-stack';

const app = new cdk.App();

new HotelInfraStack(app, 'HotelInfraStack', {
  /* Targeted specifically to ap-south-1 (Mumbai) as requested */
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: 'ap-south-1',
  },
  description: 'Hotel Booking Infrastructure (DynamoDB, Java 21 Lambda, API Gateway) for ap-south-1',
});

app.synth();
