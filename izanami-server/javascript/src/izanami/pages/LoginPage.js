import React, { Component } from 'react';
import {Form, Alerts} from '../inputs'
import * as IzanamiServices from "../services/index";
import moment from 'moment'
export class LoginPage extends Component {

  formSchema = {
    userId: { type: 'string', props: { label: 'Login', placeholder: 'Login' }},
    password: { type: 'string', props: { label: 'Password', placeholder: 'Password', type: 'password' }},
  };

  formFlow = [
    'userId',
    'password'
  ];

  state = {
    value : {
      userId :"",
      password :""
    },
    redirectToReferrer: false
  };

  clean = () => {
    this.setState({
      value : {
        userId :"",
        password :""
      },
      error : false
    })
  };

  componentDidMount() {
    window.addEventListener('keydown', this.loginOnKeyDown);
  }

  componentWillUnmount() {
    window.removeEventListener('keydown', this.loginOnKeyDown);
  }

  login = () => {
    IzanamiServices.fetchLogin(this.state.value).then(({status, body}) => {
      if (status === 200) {
        if (body.changeme) {
          const d = moment().add(1, 'minute');
          document.cookie = `notifyuser=true;expires=${d.toDate().toUTCString()};path=/`;
        }
        this.setState({error: false});
        window.location.href = window.__contextPath === '' ? '/' : window.__contextPath;
      } else {
        this.setState({error: true})
      }
    })
  };

  loginOnKeyDown = (event) => {
    if (event.key === 'Enter') {
        this.login();
    }
  };

  render() {
    return (
      <div className="container-fluid">
        <div className="text-center">
          <img className="logo_izanami_dashboard" src={`${window.__contextPath}/assets/images/izanami.png`}/>
        </div>
        <div className="col-md-4 col-md-offset-4" style={{marginTop:"20px"}}>
          <Form
            value={this.state.value}
            onChange={value => this.setState({value})}
            flow={this.formFlow}
            schema={this.formSchema}
          >
            <hr/>
            {this.state.error &&
              <div className="col-sm-offset-2 panel-group">
                <Alerts display={this.state.error} messages={[{message: "auth.invalid.login"}]}/>
              </div>
            }
            <div className="form-buttons pull-right">
              <button type="button" className="btn btn-danger" onClick={this.clean}>
                Cancel
              </button>
              <button type="button" className="btn btn-primary" onClick={this.login}>
                <i className="glyphicon glyphicon-hdd"/> Login
              </button>
            </div>
          </Form>
        </div>
      </div>
    );
  }
}
