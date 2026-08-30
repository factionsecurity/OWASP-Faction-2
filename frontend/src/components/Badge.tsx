import './Badge.css';

interface BadgeProps {
  children: React.ReactNode;
  variant?: 'primary' | 'secondary' | 'success' | 'danger' | 'warning' | 'info';
  size?: 'sm' | 'md' | 'lg';
  customColor?: string;
}

export default function Badge({ children, variant = 'primary', size = 'md', customColor }: BadgeProps) {
  // Soft same-color glow gives bright custom colors a neon pop on dark backgrounds
  const style = customColor
    ? { backgroundColor: customColor + '26', color: customColor, textShadow: `0 0 9px ${customColor}59` }
    : undefined;
  return (
    <span className={`badge${!customColor ? ` badge-${variant}` : ''} badge-${size}`} style={style}>
      {children}
    </span>
  );
}
