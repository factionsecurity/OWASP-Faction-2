import { useEffect, useState } from 'react';
import Modal from './Modal';
import DiamondIcon from './DiamondIcon';
import { UpgradeLink } from './PaidFeature';
import type { UpgradeRequired } from '../types';
import './PaidFeature.css';

/**
 * The single place a 402 becomes something a person can read.
 *
 * Listens for the `upgrade-required` event the API interceptor raises, so any request
 * blocked by the edition explains itself the same way — including one from a screen
 * that forgot to gate its own button. That makes the UI gating a convenience rather
 * than the thing correctness depends on.
 */
export default function UpgradeDialog() {
  const [blocked, setBlocked] = useState<UpgradeRequired | null>(null);

  useEffect(() => {
    const onBlocked = (event: Event) => {
      setBlocked((event as CustomEvent<UpgradeRequired>).detail ?? null);
    };
    window.addEventListener('upgrade-required', onBlocked);
    return () => window.removeEventListener('upgrade-required', onBlocked);
  }, []);

  if (!blocked) return null;

  const isQuota = blocked.code === 'QUOTA_EXCEEDED';

  return (
    <Modal
      isOpen
      onClose={() => setBlocked(null)}
      title={isQuota ? 'Edition limit reached' : 'Not in this edition'}
      size="sm"
    >
      <div className="paid-lock paid-lock--bare">
        <DiamondIcon className="paid-lock__diamond" />
        <p className="paid-lock__body">{blocked.message}</p>
        <UpgradeLink />
      </div>
    </Modal>
  );
}
