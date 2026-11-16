#!/usr/bin/env python3
"""
NASA Earthdata Authentication Module
------------------------------------
Provides secure authentication handling using official NASA earthaccess library.
Supports:
  - Interactive login via earthaccess.login(strategy="interactive", persist=True)
  - Verification of ~/.netrc credentials
  - Retrieval of temporary AWS S3 credentials for Earthdata Cloud DAACs
"""

import os
import sys
import argparse
import earthaccess

def get_auth_status() -> dict:
    """Checks whether the environment or ~/.netrc has valid NASA Earthdata authentication."""
    try:
        # Check netrc first
        auth = earthaccess.login(strategy="netrc")
        if auth.authenticated:
            return {
                "authenticated": True,
                "strategy": "netrc",
                "system": str(auth.system),
                "error": None
            }
    except Exception as e_netrc:
        pass

    try:
        # Check environment
        auth = earthaccess.login(strategy="environment")
        if auth.authenticated:
            return {
                "authenticated": True,
                "strategy": "environment",
                "system": str(auth.system),
                "error": None
            }
    except Exception as e_env:
        pass

    return {
        "authenticated": False,
        "strategy": None,
        "system": None,
        "error": "No valid credentials found in ~/.netrc or environment."
    }

def run_interactive_login(persist: bool = True) -> bool:
    """Executes earthaccess.login(strategy='interactive', persist=True)."""
    print("\n========================================================================")
    print("  NASA EARTHDATA LOGIN (INTERACTIVE AUTHENTICATION)")
    print("========================================================================")
    print("Credentials are submitted directly to NASA URS (urs.earthdata.nasa.gov)")
    print("and safely persisted to ~/.netrc with secure (0600) file permissions.\n")

    try:
        auth = earthaccess.login(strategy="interactive", persist=persist)
        if auth.authenticated:
            print("\n✅ Successfully authenticated with NASA Earthdata!")
            return True
        else:
            print("\n❌ Authentication failed. Please verify your Earthdata credentials.")
            return False
    except Exception as e:
        print(f"\n❌ Authentication error: {e}", file=sys.stderr)
        return False

def get_s3_temporary_credentials(daac: str = "GESDISC") -> dict:
    """
    Obtains temporary AWS S3 credentials for in-region cloud direct access
    (us-west-2) for supported NASA DAACs without persisting long-term keys.
    """
    status = get_auth_status()
    if not status["authenticated"]:
        raise PermissionError("Cannot obtain S3 credentials: Not authenticated with NASA Earthdata.")

    try:
        s3_creds = earthaccess.get_s3_credentials(daac=daac)
        return s3_creds
    except Exception as e:
        print(f"[WARN] S3 credentials retrieval for DAAC '{daac}' returned: {e}", file=sys.stderr)
        return {}

def main():
    parser = argparse.ArgumentParser(description="NASA Earthdata Authentication Utility")
    parser.add_argument("--login", action="store_true", help="Launch interactive login")
    parser.add_argument("--check", action="store_true", help="Check current authentication status")
    parser.add_argument("--s3-test", type=str, default="", help="Test temporary S3 credentials for a DAAC (e.g. GESDISC, LPDAAC)")

    args = parser.parse_args()

    if args.login:
        success = run_interactive_login(persist=True)
        sys.exit(0 if success else 1)

    status = get_auth_status()
    print("NASA Earthdata Authentication Status:")
    print(f"  Authenticated: {status['authenticated']}")
    if status['authenticated']:
        print(f"  Strategy:      {status['strategy']}")
        if args.s3_test:
            print(f"\nRequesting temporary S3 credentials for {args.s3_test}...")
            creds = get_s3_temporary_credentials(args.s3_test)
            if creds:
                print(f"  S3 Access Key ID: {creds.get('accessKeyId', 'N/A')[:4]}...")
                print(f"  Expiration:       {creds.get('expiration', 'N/A')}")
    else:
        print(f"  Details:       {status['error']}")

if __name__ == "__main__":
    main()
