// Firebase Functions v4+ Modular Syntax for Email Triggering

const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { onRequest } = require('firebase-functions/v2/https');
const { defineSecret } = require('firebase-functions/params');
const admin = require('firebase-admin');
const nodemailer = require('nodemailer');

// Secrets (defined in Firebase CLI)
const EMAIL_USER = defineSecret('EMAIL_USER');
const EMAIL_PASS = defineSecret('EMAIL_PASS');
const EMAIL_SERVICE = defineSecret('EMAIL_SERVICE');

// Initialize Firebase Admin SDK
admin.initializeApp();

// Email transporter setup
let emailTransporter = null;
let emailConfig = {};

async function initializeTransporter() {
  try {
    emailConfig.user = EMAIL_USER.value();
    emailConfig.password = EMAIL_PASS.value();
    emailConfig.service = EMAIL_SERVICE.value() || 'gmail';
    emailConfig.from = emailConfig.user;

    // Use a pool configuration to handle multiple concurrent requests better
    emailTransporter = nodemailer.createTransport({
      service: emailConfig.service,
      auth: {
        user: emailConfig.user,
        pass: emailConfig.password
      },
      pool: true, // Use pooled connection
      maxConnections: 5, // Maximum number of connections
      maxMessages: 100, // Maximum number of messages per connection
      rateDelta: 1000, // How many milliseconds between messages
      rateLimit: 5 // Max messages per rateDelta (5 messages per second)
    });

    console.log('✅ Email transporter initialized:', emailConfig.user);
  } catch (err) {
    console.error('❌ Error initializing transporter:', err);
    emailTransporter = nodemailer.createTransport({ jsonTransport: true });
  }
}

initializeTransporter();

// Send Email when document is created in 'email_requests' collection
exports.sendEmail = onDocumentCreated({
  document: 'email_requests/{requestId}',
  secrets: [EMAIL_USER, EMAIL_PASS, EMAIL_SERVICE],
  timeoutSeconds: 60, // Increase timeout to 60 seconds
  memory: '256MiB' // Allocate more memory for better performance
}, async (event) => {
  const snap = event.data;
  const context = event;
  const data = snap.data();
  const requestId = context.params.requestId;

  const createDeliveryStatus = async (status, details = null) => {
    try {
      const statusData = {
        email_id: requestId,
        delivered: status === 'delivered',
        status,
        timestamp: admin.firestore.FieldValue.serverTimestamp()
      };

      if (details) statusData.details = details;
      if (data.requestId) statusData.request_id = data.requestId;

      const confirmPath = data.confirm_path || 'email_delivery_status';

      // First update the request status for faster client updates
      await snap.ref.update({ status, processedAt: admin.firestore.FieldValue.serverTimestamp() });

      // Then add the detailed status document
      await admin.firestore().collection(confirmPath).add(statusData);
    } catch (err) {
      console.error('Error creating delivery status:', err);
    }
  };

  if (!data.to || !data.subject || !data.message) {
    await createDeliveryStatus('failed', 'Missing required fields');
    return { success: false, error: 'Missing required fields' };
  }

  try {
    await snap.ref.update({ processing: true, processingStart: admin.firestore.FieldValue.serverTimestamp() });

    const mailOptions = {
      from: emailConfig.from,
      to: data.to,
      subject: data.subject,
      html: data.html ? data.message : undefined,
      text: !data.html ? data.message : undefined,
      priority: data.priority || 'normal'
    };

    // Mark as processing immediately for better client feedback
    await createDeliveryStatus('processing', { from: mailOptions.from });

    // Send the email
    const result = await emailTransporter.sendMail(mailOptions);

    // Update with success
    await snap.ref.update({
      processed: true,
      status: 'delivered',
      emailId: result.messageId,
      processedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // Create detailed delivery status
    await createDeliveryStatus('delivered', { messageId: result.messageId });

    return { success: true, messageId: result.messageId };
  } catch (err) {
    // Update with failure
    await snap.ref.update({
      status: 'failed',
      error: err.message,
      processedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // Create detailed failure status
    await createDeliveryStatus('failed', { error: err.message });

    return { success: false, error: err.message };
  }
});

// Direct Email API
exports.sendDirectEmail = onDocumentCreated({ document: 'email_direct_requests/{requestId}', secrets: [EMAIL_USER, EMAIL_PASS, EMAIL_SERVICE] }, async (event) => {
  const snap = event.data;
  const context = event;
  const data = snap.data();
  const requestId = context.params.requestId;

  const createStatus = async (status, info = {}) => {
    await admin.firestore().collection('email_delivery_status').add({
      email_id: requestId,
      delivered: status === 'delivered',
      status,
      info,
      timestamp: admin.firestore.FieldValue.serverTimestamp()
    });
    await snap.ref.update({ status, processedAt: admin.firestore.FieldValue.serverTimestamp() });
  };

  try {
    const mailOptions = {
      from: emailConfig.from,
      to: data.to,
      subject: data.subject || 'Direct Email',
      html: data.html ? data.message : undefined,
      text: !data.html ? data.message : undefined
    };

    const result = await emailTransporter.sendMail(mailOptions);
    await createStatus('delivered', { messageId: result.messageId });
    return { success: true, messageId: result.messageId };
  } catch (err) {
    await createStatus('failed', { error: err.message });
    return { success: false, error: err.message };
  }
});

// Check delivery status - optimized with more useful response
exports.checkEmailDelivery = onRequest({
  timeoutSeconds: 30,
  memory: '256MiB'
}, async (req, res) => {
  try {
    const { emailId } = req.query;
    if (!emailId) return res.status(400).json({ error: 'Missing emailId parameter' });

    // Check delivery status collection first
    const statusSnap = await admin.firestore().collection('email_delivery_status')
      .where('email_id', '==', emailId).get();

    if (!statusSnap.empty) {
      const statusData = statusSnap.docs[0].data();
      return res.json({
        found: true,
        delivered: !!statusData.delivered,
        status: statusData.status,
        timestamp: statusData.timestamp,
        details: statusData.details || {}
      });
    }

    // Check the email request itself as a fallback
    const emailSnap = await admin.firestore().collection('email_requests').doc(emailId).get();
    if (emailSnap.exists) {
      const emailData = emailSnap.data();
      return res.json({
        found: true,
        delivered: emailData.status === 'delivered',
        status: emailData.status || 'unknown',
        timestamp: emailData.processedAt,
        source: 'email_request'
      });
    }

    // Check direct email requests as a last resort
    const directSnap = await admin.firestore().collection('email_direct_requests').doc(emailId).get();
    if (directSnap.exists) {
      const directData = directSnap.data();
      return res.json({
        found: true,
        delivered: directData.status === 'delivered',
        status: directData.status || 'unknown',
        timestamp: directData.processedAt,
        source: 'direct_request'
      });
    }

    return res.json({ found: false });
  } catch (err) {
    console.error('Error checking email delivery:', err);
    return res.status(500).json({ error: err.message });
  }
});

// Simple test
exports.helloWorld = onRequest((req, res) => {
  return res.json({
    message: "Hello from Security App Email Functions!",
    status: "OK",
    time: new Date().toISOString()
  });
});

// Configuration helper (SECURE VERSION)
exports.getEmailConfigInstructions = onRequest(async (req, res) => {
  try {
    return res.json({
      success: true,
      message: 'Email configuration setup instructions:',
      instructions: [
        'Set up email credentials using Firebase CLI:',
        'firebase functions:secrets:set EMAIL_USER --data "your-email@domain.com"',
        'firebase functions:secrets:set EMAIL_PASS --data "your-app-password"',
        'firebase functions:secrets:set EMAIL_SERVICE --data "gmail"',
        'firebase deploy --only functions',
        '',
        'Note: Never pass credentials via URL parameters for security reasons.'
      ]
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

