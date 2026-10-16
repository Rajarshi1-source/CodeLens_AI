import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { RiskBadge } from './RiskBadge';
import { renderWithProviders } from '@/test/render';

describe('RiskBadge', () => {
  it('labels the band in text (not color alone) with the score', () => {
    renderWithProviders(<RiskBadge level="HIGH" score={72} />);
    const badge = screen.getByTestId('risk-badge');
    expect(badge).toHaveAttribute('data-risk', 'HIGH');
    expect(badge).toHaveTextContent('High risk');
    expect(badge).toHaveTextContent('72');
  });

  it('says "Not scored" when there is no risk yet', () => {
    renderWithProviders(<RiskBadge level={null} />);
    expect(screen.getByTestId('risk-badge')).toHaveTextContent('Not scored');
  });
});
