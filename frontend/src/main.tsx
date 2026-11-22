import ReactDOM from 'react-dom/client'
import App from './App.tsx'
import './index.css'
import { clearSavedTableState } from './utils/clearSavedTableState'

clearSavedTableState()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <App />
)
