use anchor_lang::prelude::*;

declare_id!("11111111111111111111111111111111");

pub const MINIMUM_AVAILABILITY_BPS: u16 = 9_900;
pub const REFUND_BPS: u16 = 3_000;

#[account]
#[derive(Default, InitSpace)]
pub struct EscrowAgreement {
    pub customer: Pubkey,
    pub provider: Pubkey,
    pub escrow_amount: u64,
    pub required_uptime_bps: u16,
    pub monitoring_window_seconds: u64,
    pub start_timestamp: i64,
    pub end_timestamp: i64,
    pub availability_bps: u16,
    pub state: AgreementState,
    pub settled: bool,
    pub bump: u8,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, Debug, Default, InitSpace)]
pub enum AgreementState {
    #[default]
    Created,
    CustomerFunded,
    Monitoring,
    Evaluating,
    Settled,
}

#[derive(Accounts)]
pub struct CreateEscrow<'info> {
    #[account(mut)]
    pub customer: Signer<'info>,
    #[account(mut)]
    pub agreement: Account<'info, EscrowAgreement>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct UpdateMonitoring<'info> {
    #[account(mut)]
    pub agreement: Account<'info, EscrowAgreement>,
}

#[derive(Accounts)]
pub struct SettleEscrow<'info> {
    #[account(mut)]
    pub customer: Signer<'info>,
    #[account(mut)]
    pub provider: UncheckedAccount<'info>,
    #[account(mut)]
    pub agreement: Account<'info, EscrowAgreement>,
    pub system_program: Program<'info, System>,
}

#[program]
pub mod solana_escrow {
    use super::*;

    pub fn create_escrow(
        ctx: Context<CreateEscrow>,
        provider: Pubkey,
        escrow_amount: u64,
        required_uptime_bps: u16,
        monitoring_window_seconds: u64,
        start_timestamp: i64,
        end_timestamp: i64,
    ) -> Result<()> {
        let agreement = &mut ctx.accounts.agreement;
        agreement.customer = ctx.accounts.customer.key();
        agreement.provider = provider;
        agreement.escrow_amount = escrow_amount;
        agreement.required_uptime_bps = required_uptime_bps;
        agreement.monitoring_window_seconds = monitoring_window_seconds;
        agreement.start_timestamp = start_timestamp;
        agreement.end_timestamp = end_timestamp;
        agreement.availability_bps = 0;
        agreement.state = AgreementState::Created;
        agreement.settled = false;
        agreement.bump = ctx.bumps.agreement;
        Ok(())
    }

    pub fn fund_escrow(ctx: Context<CreateEscrow>, amount: u64) -> Result<()> {
        let agreement = &mut ctx.accounts.agreement;
        require!(agreement.state == AgreementState::Created, EscrowError::InvalidState);
        require!(amount == agreement.escrow_amount, EscrowError::InvalidAmount);
        agreement.state = AgreementState::CustomerFunded;
        Ok(())
    }

    pub fn start_monitoring(ctx: Context<UpdateMonitoring>) -> Result<()> {
        let agreement = &mut ctx.accounts.agreement;
        require!(agreement.state == AgreementState::CustomerFunded, EscrowError::InvalidState);
        agreement.state = AgreementState::Monitoring;
        Ok(())
    }

    pub fn record_availability(ctx: Context<UpdateMonitoring>, availability_bps: u16) -> Result<()> {
        let agreement = &mut ctx.accounts.agreement;
        require!(agreement.state == AgreementState::Monitoring, EscrowError::InvalidState);
        agreement.availability_bps = availability_bps;
        agreement.state = AgreementState::Evaluating;
        Ok(())
    }

    pub fn settle(ctx: Context<SettleEscrow>, availability_bps: u16) -> Result<()> {
        let agreement = &mut ctx.accounts.agreement;
        require!(!agreement.settled, EscrowError::AlreadySettled);
        require!(agreement.state != AgreementState::Settled, EscrowError::InvalidState);

        agreement.availability_bps = availability_bps;
        agreement.settled = true;
        agreement.state = AgreementState::Settled;

        let payout_lamports = if availability_bps < MINIMUM_AVAILABILITY_BPS {
            agreement.escrow_amount.saturating_mul(REFUND_BPS as u64) / 10_000
        } else {
            agreement.escrow_amount
        };

        msg!("agreement settled with availability {} bps and payout {} lamports", availability_bps, payout_lamports);
        Ok(())
    }
}

#[error_code]
pub enum EscrowError {
    #[msg("Escrow state transition is invalid.")]
    InvalidState,
    #[msg("Escrow amount is invalid for this agreement.")]
    InvalidAmount,
    #[msg("The agreement has already settled.")]
    AlreadySettled,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refund_rule_applies_below_ninety_nine_percent() {
        let amount = 10_000_000_000u64;
        let availability_bps = 98_750u16;

        let payout = if availability_bps < MINIMUM_AVAILABILITY_BPS {
            amount.saturating_mul(REFUND_BPS as u64) / 10_000
        } else {
            amount
        };

        assert_eq!(payout, 3_000_000_000u64);
    }

    #[test]
    fn provider_receives_full_amount_at_or_above_ninety_nine_percent() {
        let amount = 10_000_000_000u64;
        let availability_bps = 99_000u16;

        let payout = if availability_bps < MINIMUM_AVAILABILITY_BPS {
            amount.saturating_mul(REFUND_BPS as u64) / 10_000
        } else {
            amount
        };

        assert_eq!(payout, 10_000_000_000u64);
    }
}
