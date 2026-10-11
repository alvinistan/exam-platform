import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Alert, Badge, Button, ErrorState, TextField } from '.';

describe('Button', () => {
  it('runs its click handler', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Start</Button>);
    await userEvent.click(screen.getByRole('button', { name: 'Start' }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('is disabled and announced as busy while loading', async () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Saving
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Saving' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('defaults to type="button" so it never submits a form by accident', () => {
    render(<Button>Next</Button>);
    expect(screen.getByRole('button')).toHaveAttribute('type', 'button');
  });
});

describe('Badge', () => {
  it('carries its meaning in text, not only colour', () => {
    render(<Badge tone="premium">Premium</Badge>);
    expect(screen.getByText('Premium')).toBeInTheDocument();
  });
});

describe('Alert', () => {
  it('announces errors immediately', () => {
    render(<Alert tone="error" title="Payment failed" />);
    expect(screen.getByRole('alert')).toHaveTextContent('Payment failed');
  });

  it('uses a polite status for information', () => {
    render(<Alert tone="success" title="Saved" />);
    expect(screen.getByRole('status')).toHaveTextContent('Saved');
  });
});

describe('TextField', () => {
  it('links the label, help text and error to the input', () => {
    render(<TextField label="Exam title" help="Shown to students" error="Title is required" />);
    const input = screen.getByLabelText('Exam title');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Shown to students Title is required');
  });

  it('is not marked invalid without an error', () => {
    render(<TextField label="Slug" />);
    expect(screen.getByLabelText('Slug')).not.toHaveAttribute('aria-invalid');
  });
});

describe('ErrorState', () => {
  it('shows the support reference and retries on request', async () => {
    const onRetry = vi.fn();
    render(<ErrorState onRetry={onRetry} correlationId="abc-123" />);
    expect(screen.getByText('Reference: abc-123')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
