import { LucideIcon } from 'lucide-react';

interface PlaceholderProps {
  title: string;
  icon: LucideIcon;
  description: string;
}

export default function Placeholder({ title, icon: Icon, description }: PlaceholderProps) {
  return (
    <div style={{ textAlign: 'center', padding: '4rem 2rem' }}>
      <div style={{
        display: 'inline-flex',
        padding: '2rem',
        marginBottom: '1.5rem',
        borderRadius: '1rem',
        backgroundColor: 'var(--tertiary-bg)',
        color: 'var(--text-muted)'
      }}>
        <Icon size={64} strokeWidth={1.5} />
      </div>
      <h2 style={{ fontSize: '2rem', marginBottom: '1rem', color: 'var(--text-primary)' }}>{title}</h2>
      <p style={{ color: 'var(--text-muted)', fontSize: '1.125rem' }}>{description}</p>
    </div>
  );
}
