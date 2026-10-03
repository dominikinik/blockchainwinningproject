import { Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { DashboardPage } from './pages/DashboardPage'
import { CreateSLAPage } from './pages/CreateSLAPage'
import { SLADetailsPage } from './pages/SLADetailsPage'
import { MonitoringPage } from './pages/MonitoringPage'
import { UptimeDealPage } from './pages/UptimeDealPage'
import { NotFoundPage } from './pages/NotFoundPage'

export function App() {
  return <Routes>
    <Route element={<AppShell />}>
      <Route path="/" element={<DashboardPage />} />
      <Route path="/create" element={<CreateSLAPage />} />
      <Route path="/sla/:id" element={<SLADetailsPage />} />
      <Route path="/monitoring" element={<MonitoringPage />} />
      <Route path="/deal" element={<UptimeDealPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Route>
  </Routes>
}
