import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { EmptyState } from '../components/UI'

export function NotFoundPage() {
  return <EmptyState title="Page not found" description="The page you opened does not exist." action={<Link to="/" className="button button-primary">Back to dashboard <ArrowRight size={16} /></Link>} />
}
