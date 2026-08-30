# FACTION Admin Portal - Complete Setup Guide

This guide will walk you through setting up both the backend and frontend for the FACTION Admin Portal.

## Project Structure

```
claude-version/
├── backend/           # Spring Boot REST API
├── frontend/          # React Admin UI
└── README.md         # This file
```

## Prerequisites

- **Java 17** - For backend
- **Maven 3.9.9** - For backend
- **Docker** - For MongoDB
- **Node.js 20.11.0** - For frontend (managed via asdf)
- **asdf** - Version manager (optional but recommended)

## Quick Start

### Step 1: Start MongoDB

```bash
cd backend
docker compose up -d
```

### Step 2: Start Backend API

```bash
cd backend
mvn clean install
mvn spring-boot:run
```

The backend will be available at `http://localhost:8080`

### Step 3: Copy Frontend Logo

```bash
cp ~/Pictures/faction-white-logo.png frontend/public/
```

### Step 4: Install Frontend Dependencies

```bash
cd frontend
npm install
```

### Step 5: Start Frontend

```bash
cd frontend
npm run dev
```

The frontend will be available at `http://localhost:3000`

### Step 6: Login

Navigate to `http://localhost:3000` and login with:

- **Username**: `admin`
- **Password**: `admin123`

## Detailed Setup

### Backend Setup

1. **Navigate to backend directory**
   ```bash
   cd backend
   ```

2. **Start MongoDB**
   ```bash
   docker compose up -d
   ```

3. **Verify MongoDB is running**
   ```bash
   docker compose ps
   ```

4. **Build and run the backend**
   ```bash
   mvn clean install
   mvn spring-boot:run
   ```

5. **Verify backend is running**
   ```bash
   curl http://localhost:8080/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","password":"admin123"}'
   ```

6. **Access Swagger UI** (optional)

   Visit: `http://localhost:8080/swagger-ui/index.html`

### Frontend Setup

1. **Navigate to frontend directory**
   ```bash
   cd frontend
   ```

2. **Ensure Node.js 20.11.0 is active**
   ```bash
   asdf install  # Installs version from .tool-versions
   node --version  # Should show v20.11.0
   ```

3. **Install dependencies**
   ```bash
   npm install
   ```

4. **Copy the FACTION logo**
   ```bash
   mkdir -p public
   cp ~/Pictures/faction-white-logo.png public/
   ```

5. **Start the development server**
   ```bash
   npm run dev
   ```

6. **Open browser**

   Navigate to: `http://localhost:3000`

## Email (Optional)

Email is off by default and the application runs fine without it. Turn it on for @mention
notifications; add IMAP on top if you want people to be able to reply from their inbox.

### Outbound (SMTP)

**Administration → Email Config**, as a super admin. Set the provider, host, credentials and
from-address, then use **Send test email** before anything else. The enable switch saves on its own.

Set `SSO_ENCRYPTION_KEY` before entering credentials. Without it the SMTP and IMAP passwords are
stored in plaintext — the same key encrypts both.

### Inbound (IMAP) — reply by email

**Administration → Inbound Email**. Set the reply address and IMAP details, then **Test IMAP
Connection**.

The mailbox has three requirements:

1. **Dedicated to Faction.** The poller marks messages read and may move them into a processed
   folder. Do not point it at a person's inbox.
2. **Accepts plus-addressing.** The per-thread reply token travels as
   `faction+<token>@yourcompany.com`. Gmail and Google Workspace support this out of the box;
   Microsoft 365 needs sub-addressing enabled for the tenant.
3. **IMAP enabled**, with an app password where the provider requires one.

Leave the reply address blank and mention emails still send — they simply omit the reply invitation
rather than inviting a reply that cannot be received.

### Checking it works

Mentioning **yourself does nothing** — self-mentions are skipped by design, as in Slack and GitHub.
Use two accounts, and give each a real deliverable address.

Delivery takes 10–25 seconds: a 10-second debounce drained on a 15-second tick.

Every inbound message is recorded, so start there when a reply does not appear:

```sql
SELECT received_at, from_address, status, reason FROM inbound_email_log
ORDER BY received_at DESC LIMIT 20;
```

Full reference: [documentation/email-integration.md](./documentation/email-integration.md)

## Verification Checklist

- [ ] MongoDB is running (`docker compose ps`)
- [ ] Backend is running on port 8080
- [ ] Backend health check passes (can login via API)
- [ ] Frontend is running on port 3000
- [ ] Logo is visible in `/public` directory
- [ ] Can login to the admin portal
- [ ] Can navigate between pages
- [ ] User management page shows users
- [ ] (If email is configured) Send test email succeeds from Administration → Email Config
- [ ] (If inbound is configured) Test IMAP Connection succeeds from Administration → Inbound Email

## Default Users

The backend creates these users automatically on first startup:

1. **Super Admin**
   - Username: `admin`
   - Password: `admin123`
   - Full access to all features

2. **Pentester**
   - Username: `pentest`
   - Password: `pentest123`
   - Limited access for penetration testing

⚠️ **Important**: Change these passwords in production!

## Common Issues

### Backend Issues

**Problem**: MongoDB connection failed

**Solution**:
```bash
cd backend
docker compose down
docker compose up -d
docker compose logs -f mongodb
```

**Problem**: Port 8080 already in use

**Solution**:
```bash
lsof -ti:8080 | xargs kill -9
```

### Frontend Issues

**Problem**: Cannot connect to backend

**Solution**: Ensure backend is running on port 8080

**Problem**: Logo not showing

**Solution**:
```bash
cp ~/Pictures/faction-white-logo.png frontend/public/
```

**Problem**: Port 3000 already in use

**Solution**: Edit `frontend/vite.config.ts` and change the port

## Development Workflow

### Terminal 1: Backend
```bash
cd backend
mvn spring-boot:run
```

### Terminal 2: Frontend
```bash
cd frontend
npm run dev
```

### Terminal 3: MongoDB (optional - for logs)
```bash
cd backend
docker compose logs -f mongodb
```

## Testing

### Test Backend
```bash
cd backend
mvn test
```

All 119 tests should pass.

### Test Frontend
```bash
cd frontend
npm run lint
```

### Manual Testing
1. Login as `admin`
2. Navigate to Users page
3. Create a new user
4. Edit the user
5. Delete the user

## API Documentation

Once the backend is running, access the API documentation:

- **Swagger UI**: http://localhost:8080/swagger-ui/index.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs

## Production Deployment

### Backend
See `backend/README.md` for detailed deployment instructions including:
- Docker deployment
- Kubernetes
- AWS Elastic Beanstalk
- Heroku

### Frontend

1. **Build the frontend**
   ```bash
   cd frontend
   npm run build
   ```

2. **Deploy the `dist/` directory** to:
   - Netlify
   - Vercel
   - AWS S3 + CloudFront
   - Nginx/Apache

3. **Configure API endpoint**

   Update the API base URL in production to point to your backend server.

## Architecture Overview

```
┌─────────────┐         ┌──────────────┐         ┌──────────┐
│   Browser   │         │   Frontend   │         │ Backend  │
│             │◄───────►│   React      │◄───────►│  Spring  │
│  localhost  │  HTTP   │  (Port 3000) │  REST   │  Boot    │
│  :3000      │         │              │  API    │ (8080)   │
└─────────────┘         └──────────────┘         └────┬─────┘
                                                       │
                                                       │ JDBC
                                                       ▼
                                                  ┌────────┐
                                                  │MongoDB │
                                                  │  (DB)  │
                                                  │ 27017  │
                                                  └────────┘
```

## Features Implemented

### Backend (Spring Boot)
✅ JWT Authentication
✅ User Management (CRUD)
✅ Role Management
✅ Organization Management
✅ Application Management
✅ Team Management
✅ Permission System (44 permissions)
✅ OpenAPI/Swagger Documentation
✅ 119 Comprehensive Tests

### Frontend (React)
✅ Login Page (Password auth)
✅ Dashboard Layout with Sidebar
✅ User Management Page (Full CRUD)
✅ Dark Theme UI
✅ Responsive Design
✅ Role Assignment
✅ Navigation Menu
✅ Logout Functionality

### Placeholder Pages
- Organizations (UI pending)
- Applications (UI pending)
- Assessments (UI pending)
- Vulnerabilities (UI pending)
- Engagement (UI pending)
- Remediation (UI pending)

## Next Steps

1. **Implement Organization Management UI**
2. **Implement Application Management UI**
3. **Add SAML/OpenID authentication**
4. **Add advanced filtering and search**
5. **Add data visualization/charts**
6. **Add export functionality**
7. **Add audit logs**
8. **Add notifications**

## Support

For issues or questions:
1. Check the troubleshooting section
2. Review the API documentation
3. Check backend/frontend logs
4. Contact the development team

---

**Ready to build secure applications with FACTION Security!** 🚀
