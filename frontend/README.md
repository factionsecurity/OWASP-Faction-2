# FACTION Admin Portal - Frontend

Modern React-based administration UI with dark theme for managing the FACTION Security platform.

## Features

- 🎨 **Dark Theme** - Professional cybersecurity-themed dark UI
- 🔐 **Authentication** - JWT-based login with SSO placeholders
- 👥 **User Management** - Full CRUD operations for users
- 📊 **Dashboard** - Overview and statistics
- 🎯 **Role-Based Access** - Integrated with backend permissions
- 📱 **Responsive** - Works on desktop and mobile devices

## Tech Stack

- **React 18** - UI framework
- **TypeScript** - Type safety
- **Vite** - Build tool and dev server
- **React Router** - Client-side routing
- **Axios** - HTTP client
- **CSS3** - Custom styling with CSS variables

## Prerequisites

- Node.js 20.11.0 (managed via asdf)
- Backend API running on `http://localhost:8080`

## Setup

### 1. Install Dependencies

```bash
npm install
```

### 2. Copy Logo

Copy the FACTION logo to the public directory:

```bash
cp ~/Pictures/faction-white-logo.png public/
```

### 3. Start Development Server

```bash
npm run dev
```

The application will be available at `http://localhost:3000`

## Project Structure

```
src/
├── components/          # Reusable components
│   ├── Login.tsx       # Login page with SSO options
│   ├── Login.css
│   ├── DashboardLayout.tsx  # Main layout with sidebar
│   └── DashboardLayout.css
├── pages/              # Page components
│   ├── Dashboard.tsx   # Dashboard page (placeholder)
│   ├── Users.tsx       # User management page (full CRUD)
│   ├── Placeholder.tsx # Placeholder for未implemented pages
│   └── *.css          # Page styles
├── api.ts              # API service layer
├── types.ts            # TypeScript interfaces
├── main.tsx            # Application entry point
├── App.tsx             # Main app component with routing
└── index.css           # Global styles and theme
```

## Available Scripts

- `npm run dev` - Start development server
- `npm run build` - Build for production
- `npm run preview` - Preview production build
- `npm run lint` - Run ESLint

## Features by Page

### Login Page
- Username/password authentication
- Placeholder buttons for SAML and OpenID (not yet implemented)
- Displays FACTION logo
- Internal users only message

### Dashboard
- Statistics cards (placeholder data)
- Recent activity (placeholder)
- System overview (placeholder)

### Users Management
- ✅ View all users in a table
- ✅ Create new users
- ✅ Edit existing users
- ✅ Delete users
- ✅ Assign roles to users
- ✅ View user status (Active/Disabled/Deleted)
- ✅ View last login information

### Other Pages (Placeholders)
- Organizations
- Applications
- Assessments
- Vulnerabilities
- Engagement
- Remediation

## Default Credentials

For testing, use these backend credentials:

- **Username**: `admin`
- **Password**: `admin123`

## API Integration

The frontend automatically proxies API requests to the backend:

- API Base URL: `/api/v1`
- Proxy Target: `http://localhost:8080`
- Auth: Bearer token stored in localStorage

## Theme Customization

The dark theme uses CSS variables defined in `src/index.css`:

```css
:root {
  --primary-bg: #0a0e1a;
  --secondary-bg: #111827;
  --primary-color: #3b82f6;
  --accent-color: #8b5cf6;
  /* ... more variables */
}
```

Modify these to customize the color scheme.

## Building for Production

1. Build the frontend:
```bash
npm run build
```

2. The built files will be in the `dist/` directory

3. Serve with any static file server:
```bash
npm run preview
```

Or deploy to:
- Netlify
- Vercel
- AWS S3 + CloudFront
- Nginx/Apache

## Development Tips

1. **Hot Reload**: Changes to source files automatically reload in the browser
2. **API Proxy**: Configured in `vite.config.ts` to proxy `/api` to backend
3. **TypeScript**: Type checking helps catch errors before runtime
4. **Component Structure**: Keep components small and focused

## Troubleshooting

### Cannot connect to backend

Ensure the Spring Boot backend is running:
```bash
cd ../backend
mvn spring-boot:run
```

### Port 3000 already in use

Change the port in `vite.config.ts`:
```typescript
export default defineConfig({
  server: {
    port: 3001, // Change to any available port
  }
})
```

### Styles not loading

Clear browser cache and restart dev server:
```bash
npm run dev
```

## Future Enhancements

- [ ] Implement SAML authentication
- [ ] Implement OpenID Connect authentication
- [ ] Add organization management
- [ ] Add application management
- [ ] Add assessment workflow
- [ ] Add vulnerability tracking
- [ ] Add real-time notifications
- [ ] Add data export functionality
- [ ] Add advanced search and filtering
- [ ] Add user activity logs

## Contributing

1. Create a feature branch
2. Make your changes
3. Test thoroughly
4. Submit a pull request

---

**Built with React + TypeScript + Vite**
